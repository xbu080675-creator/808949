package dev.tavo.engine;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

final class EngineJobs {
    private static final ExecutorService EXEC=Executors.newSingleThreadExecutor();
    private static Context app;
    private static AtomicFile disk;
    private static File dir;
    private static boolean loaded=false,active=false,cancel=false;
    private static Thread worker;
    private static long sessionStart,elapsedBefore;
    private static int sessionDone;
    private static Map<String,Object> job=McpServer.obj("phase","idle","done",0,"total",0,"stage","暂无任务");
    private static Map<String,Object> shared;
    private static boolean remoteAllowed;
    private static final int MAX_QUEUE=8,MAX_HISTORY=12;
    private static final List<Map<String,Object>> queue=new ArrayList<>();
    private static File historyDir(){return new File(dir,"history40");}
    private static AtomicFile queueFile(){return new AtomicFile(new File(dir,"queue40.json"));}
    @SuppressWarnings("unchecked")
    private static void loadQueue(){
        try{Object value=new McpServer.Parser(new String(queueFile().readFully(),StandardCharsets.UTF_8)).parse();if(value instanceof List)for(Object x:(List<?>)value)if(x instanceof Map&&queue.size()<MAX_QUEUE)queue.add((Map<String,Object>)x);}
        catch(Exception ignored){}
    }
    private static void writeAtomic(AtomicFile file,Object value)throws IOException{
        byte[] data=McpServer.json(value).getBytes(StandardCharsets.UTF_8);if(data.length>2000000)throw new IOException("record exceeds storage limit");
        FileOutputStream out=null;try{out=file.startWrite();out.write(data);file.finishWrite(out);}catch(IOException e){if(out!=null)file.failWrite(out);throw e;}
    }
    private static void saveQueue()throws IOException{writeAtomic(queueFile(),queue);}
    private static Map<String,Object> newJob(Uri uri){
        return McpServer.obj("id",UUID.randomUUID().toString(),"uri",uri.toString(),"model",ModelStore.selected(app).name(),"phase","queued","stage","准备本机分析","done",0,"total",0,"elapsedMs",0,"media",McpServer.obj("languageMode",VisionSettings.language(app),"ocrMode",VisionSettings.ocr(app),"visionMode",VisionSettings.visual(app),"overlapEnabled",true));
    }
    static synchronized void enqueue(Uri uri)throws Exception{
        if(queue.size()>=MAX_QUEUE)throw new IllegalStateException("队列最多8项");
        if(queue.stream().anyMatch(x->uri.toString().equals(x.get("uri")))||uri.toString().equals(job.get("uri")))throw new IllegalStateException("已在任务列表中");
        Map<String,Object> item=newJob(uri);queue.add(item);try{saveQueue();}catch(IOException e){queue.remove(item);throw e;}
    }
    static synchronized List<String> queueLabels(){List<String> names=new ArrayList<>();for(Map<String,Object> x:queue)names.add("任务 "+string(x,"id").substring(0,8)+" · "+string(x,"model"));return names;}
    static synchronized void clearQueue()throws Exception{
        List<Map<String,Object>> old=new ArrayList<>(queue);queue.clear();try{saveQueue();}catch(IOException e){queue.addAll(old);throw e;}
        for(Map<String,Object> x:old)releaseIfUnused(string(x,"uri"));
    }
    private static void releaseIfUnused(String uri){
        if(uri.isEmpty()||uri.equals(job.get("uri"))||queue.stream().anyMatch(x->uri.equals(x.get("uri"))))return;
        try{app.getContentResolver().releasePersistableUriPermission(Uri.parse(uri),Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
    }
    private static void archive()throws IOException{
        if(!"done".equals(job.get("phase")))return;
        String id=string(job,"id");if(!id.matches("[a-f0-9-]{36}"))return;
        File h=historyDir();if(!h.exists()&&!h.mkdirs())throw new IOException("history unavailable");
        writeAtomic(new AtomicFile(new File(h,id+".json")),McpServer.obj("id",id,"completedAt",System.currentTimeMillis(),"elapsedMs",number(job,"elapsedMs"),"media",media()));
        File[] records=h.listFiles((d,n)->n.matches("[a-f0-9-]{36}\\.json"));if(records!=null){Arrays.sort(records,Comparator.comparingLong(File::lastModified).reversed());for(int i=MAX_HISTORY;i<records.length;i++)new AtomicFile(records[i]).delete();}
    }
    static synchronized List<String> historyIds(){
        File[] files=historyDir().listFiles((d,n)->n.matches("[a-f0-9-]{36}\\.json"));List<String> ids=new ArrayList<>();
        if(files!=null){Arrays.sort(files,Comparator.comparingLong(File::lastModified).reversed());for(File f:files)ids.add(f.getName().substring(0,36));}return ids;
    }
    @SuppressWarnings("unchecked")
    static synchronized Map<String,Object> history(String id)throws Exception{
        if(!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("invalid id");
        Object x=new McpServer.Parser(new String(new AtomicFile(new File(historyDir(),id+".json")).readFully(),StandardCharsets.UTF_8)).parse();
        if(!(x instanceof Map)||!(((Map<?,?>)x).get("media") instanceof Map))throw new IOException("invalid record");return cloneMap((Map<String,Object>)((Map<?,?>)x).get("media"));
    }
    static synchronized void shareHistory(String id)throws Exception{shared=history(id);}
    static synchronized void clearHistory(){for(String id:historyIds())new AtomicFile(new File(historyDir(),id+".json")).delete();shared=null;}
    static synchronized void next()throws Exception{
        if(active||queue.isEmpty())throw new IllegalStateException("没有可启动的排队任务");
        if(job.get("uri")!=null&&!"done".equals(job.get("phase")))throw new IllegalStateException("请先继续或清除当前任务");
        Map<String,Object> item=queue.get(0);replaceJob(item);queue.remove(0);saveQueue();launch();
    }
    private static void replaceJob(Map<String,Object> next)throws Exception{
        archive();String old=string(job,"uri");Map<String,Object> prior=job;job=next;
        try{persist();}catch(IOException e){job=prior;throw e;}
        preview().delete();shared=null;remoteAllowed=false;releaseIfUnused(old);
    }
    static long number(Map<String,Object> x,String key){Object n=x.get(key);return n instanceof Number?((Number)n).longValue():0;}
    static String string(Map<String,Object> x,String key){return String.valueOf(x.getOrDefault(key,""));}
    @SuppressWarnings("unchecked")
    private static Map<String,Object> cloneMap(Map<String,Object> m){return (Map<String,Object>)new McpServer.Parser(McpServer.json(m)).parse();}
    @SuppressWarnings("unchecked")
    private static Map<String,Object> media(){Object m=job.get("media");return m instanceof Map?(Map<String,Object>)m:McpServer.obj();}
    static synchronized void init(Context context){
        if(loaded)return;
        app=context.getApplicationContext();
        dir=new File(app.getFilesDir(),"job30");dir.mkdirs();
        disk=new AtomicFile(new File(dir,"job.json"));
        try{String text=new String(disk.readFully(),StandardCharsets.UTF_8);
            if(text.length()>2000000)throw new IOException("checkpoint too large");
            Object value=new McpServer.Parser(text).parse();
            if(!(value instanceof Map))throw new IOException("bad checkpoint");
            job=cloneMap((Map<String,Object>)value);
            if("running".equals(job.get("phase"))){job.put("phase","interrupted");job.put("stage","上次任务中断 · 可继续");persist();}
        }catch(FileNotFoundException ignored){}catch(Exception e){job=McpServer.obj("phase","failed","stage","检查点读取失败，请重新选择视频","done",0,"total",0);}
        loadQueue();queue.removeIf(x->Objects.equals(x.get("id"),job.get("id")));try{saveQueue();}catch(IOException ignored){}
        loaded=true;
    }
    private static void persist()throws IOException{
        byte[] data=McpServer.json(job).getBytes(StandardCharsets.UTF_8);
        if(data.length>2000000)throw new IOException("checkpoint exceeds storage limit");
        FileOutputStream out=null;
        try{out=disk.startWrite();out.write(data);disk.finishWrite(out);}catch(IOException e){if(out!=null)disk.failWrite(out);throw e;}
    }
    private static long elapsed(){return active?elapsedBefore+Math.max(0,SystemClock.elapsedRealtime()-sessionStart):number(job,"elapsedMs");}
    static synchronized boolean running(){return active;}
    static synchronized Map<String,Object> snapshot(){
        long done=number(job,"done"),total=number(job,"total"),ms=elapsed();
        boolean complete="done".equals(job.get("phase"));
        long pct=Timeline.progress(done,total);
        Long eta=active?Timeline.eta(done,total,sessionDone,SystemClock.elapsedRealtime()-sessionStart):null;
        return McpServer.obj("jobId",job.get("id"),"phase",job.get("phase"),"taskState",job.get("stage"),"progressPercent",pct,"completedUnits",done,"totalUnits",total,"elapsedSeconds",ms/1000,"etaSeconds",eta,"cancelAuthorized",remoteAllowed,"completed",complete,"canResume",!active&&job.get("uri")!=null&&!complete,"queuedCount",queue.size(),"javaHeapUsedMiB",(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())/1048576L,"nativeHeapMiB",android.os.Debug.getNativeHeapAllocatedSize()/1048576L);
    }
    static synchronized File preview(){return new File(dir,"preview.jpg");}
    static synchronized Map<String,Object> completed(){return "done".equals(job.get("phase"))?cloneMap(media()):null;}
    static synchronized Map<String,Object> shared(){return shared==null?null:cloneMap(shared);}
    static synchronized boolean share(String id){if(!Objects.equals(id,job.get("id"))||!"done".equals(job.get("phase")))return false;shared=cloneMap(media());return true;}
    static synchronized void revoke(){shared=null;remoteAllowed=false;}
    static synchronized void allowCancel(){remoteAllowed=active;}
    static synchronized boolean cancelRemote(){if(!remoteAllowed||!active)return false;requestCancel();return true;}
    static synchronized void requestCancel(){if(!active)return;cancel=true;remoteAllowed=false;job.put("stage","正在取消 · 等待当前推理退出");if(worker!=null)worker.interrupt();}
    static synchronized void clear()throws Exception{
        if(active)throw new IllegalStateException("请先取消当前任务");
        String uri=string(job,"uri");
        disk.delete();preview().delete();job=McpServer.obj("phase","idle","done",0,"total",0,"stage","暂无任务");revoke();releaseIfUnused(uri);
    }
    static synchronized void start(Uri uri)throws Exception{
        if(active){enqueue(uri);return;}
        if(job.get("uri")!=null&&!"done".equals(job.get("phase"))){enqueue(uri);return;}
        replaceJob(newJob(uri));launch();
    }
    static synchronized void resume()throws Exception{
        if(active||job.get("uri")==null||"done".equals(job.get("phase")))throw new IllegalStateException("没有可恢复的任务");
        launch();
    }
    private static void launch()throws Exception{
        cancel=false;remoteAllowed=false;shared=null;elapsedBefore=number(job,"elapsedMs");sessionDone=(int)number(job,"done");sessionStart=SystemClock.elapsedRealtime();
        job.put("phase","running");job.put("stage","准备 / 恢复分析");persist();active=true;
        final Uri uri=Uri.parse(string(job,"uri"));final ModelStore.Model model=ModelStore.Model.valueOf(string(job,"model"));
        final Map<String,Object> prior=cloneMap(media());
        EXEC.execute(()->{
            PowerManager.WakeLock lock=null;
            try{
                synchronized(EngineJobs.class){worker=Thread.currentThread();if(cancel)throw new InterruptedException();}
                PowerManager pm=app.getSystemService(PowerManager.class);lock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"TavoEngine:media");lock.setReferenceCounted(false);lock.acquire(2*60*60*1000L);
                Map<String,Object> result=MediaPipeline.analyze(app,uri,dir,prior,model,(m,done,total,stage)->{
                    synchronized(EngineJobs.class){
                        if(cancel||Thread.currentThread().isInterrupted())throw new InterruptedException();
                        job.put("media",cloneMap(m));job.put("done",done);job.put("total",total);job.put("stage",stage);job.put("elapsedMs",elapsed());persist();
                    }
                });
                synchronized(EngineJobs.class){
                    if(cancel)throw new InterruptedException();
                    job.put("media",cloneMap(result));job.put("phase","done");
                    boolean partial=number(result,"audioSkippedChunks")>0||number(result,"audioFailedChunks")>0||number(result,"labelFailures")>0||number(result,"ocrFailures")>0||number(result,"vlmFailures")>0||(!"labels".equals(result.getOrDefault("visionMode","labels"))&&"yes".equals(result.get("hasVideo"))&&number(result,"vlmFrames")==0);
                    job.put("stage",partial?"已结束 · 部分分析失败或跳过，请检查报告":"已完成 · 请检查并授权分享");
                    job.put("elapsedMs",elapsed());persist();
                }
            }catch(InterruptedException e){synchronized(EngineJobs.class){job.put("phase","interrupted");job.put("stage","已停止 · 可从检查点继续");job.put("elapsedMs",elapsed());try{persist();}catch(IOException ignored){job.put("stage","已停止 · 检查点保存失败");}}}
            catch(Exception|LinkageError e){synchronized(EngineJobs.class){job.put("phase","failed");job.put("stage","处理失败 · "+e.getClass().getSimpleName()+" · 可尝试继续");job.put("elapsedMs",elapsed());try{persist();}catch(IOException ignored){job.put("stage","处理失败 · 检查点保存失败");}}}
            finally{if(lock!=null&&lock.isHeld())lock.release();synchronized(EngineJobs.class){worker=null;active=false;remoteAllowed=false;cancel=false;}Thread.interrupted();}
        });
    }
}
