package dev.tavo.engine;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends Activity {
    static final int PORT=8765;
    static volatile String serviceState="未启动";
    static String token(Context c){SharedPreferences p=c.getSharedPreferences("engine",MODE_PRIVATE);String t=p.getString("token",null);if(t==null){t=UUID.randomUUID().toString()+UUID.randomUUID().toString();p.edit().putString("token",t).apply();}return t;}
    static Map<String,Object> status(){return McpServer.obj("engine","tavo-multimodal-engine","version","0.4.0","visionReady",false,"vlmRuntimeBundled",VlmBridge.available(),"visualLabelingBundled",true,"audioReady",false,"whisperRuntimeLoaded",WhisperBridge.AVAILABLE,"status",serviceState);}
    static Map<String,Object> result(){
        Map<String,Object> media=EngineJobs.shared();
        Map<String,Object> r=status();
        r.put("kind",media!=null&&(media.containsKey("labels")||media.containsKey("transcript"))?"analysis":"metadata-only");
        r.put("media",media);r.put("warning","Explicitly shared data only. Labels are approximate, not visual-language descriptions. Missing transcript means unknown audio.");return r;
    }
    static Map<String,Object> taskStatus(){Map<String,Object> r=status();r.putAll(EngineJobs.snapshot());return r;}
    static Map<String,Object> cancelRemote(){Map<String,Object> m=status();m.put("cancelRequested",EngineJobs.cancelRemote());return m;}
    public static class EngineService extends Service {
        McpServer server;Handler h=new Handler(Looper.getMainLooper());NotificationManager nm;String notifiedJob="";
        final ExecutorService imports=Executors.newSingleThreadExecutor();volatile boolean stopped=false,copying=false;
        void copyShared(Uri uri){
            if(copying||importingModel){Toast.makeText(this,"已有导入任务",Toast.LENGTH_SHORT).show();return;}
            copying=true;imports.execute(()->{
                File folder=new File(getFilesDir(),"shared40");folder.mkdirs();File out=new File(folder,UUID.randomUUID()+".media");
                boolean submitted=false;
                try{
                    if(!"content".equals(uri.getScheme()))throw new java.io.IOException("unsupported URI");
                    long bytes=0;try(java.io.InputStream in=getContentResolver().openInputStream(uri);java.io.FileOutputStream stream=new java.io.FileOutputStream(out)){
                        if(in==null)throw new java.io.IOException("unreadable media");byte[] buf=new byte[262144];int n;
                        while((n=in.read(buf))!=-1){if(stopped||Thread.currentThread().isInterrupted())throw new InterruptedException();bytes+=n;if(bytes>200L*1024*1024)throw new java.io.IOException("share exceeds 200 MiB");stream.write(buf,0,n);}stream.getFD().sync();
                    }
                    if(bytes==0||stopped)throw new java.io.IOException("empty or stopped");
                    EngineJobs.start(Uri.fromFile(out));submitted=true;
                    h.post(()->Toast.makeText(this,"已接收媒体，开始分析或加入队列",Toast.LENGTH_SHORT).show());
                }catch(Exception e){h.post(()->Toast.makeText(this,"媒体接收失败："+e.getClass().getSimpleName(),Toast.LENGTH_LONG).show());}
                finally{if(!submitted)out.delete();copying=false;}
            });
        }
        Notification notification(){
            Map<String,Object> j=EngineJobs.snapshot();int p=(int)EngineJobs.number(j,"progressPercent");
            String stage=EngineJobs.string(j,"taskState");boolean running=EngineJobs.running();if(copying)stage="正在接收共享媒体 · "+stage;Object eta=j.get("etaSeconds");if(running&&eta!=null)stage+=" · 估计剩余 "+eta+" 秒";
            PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class).putExtra("tasks",true),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder b=new Notification.Builder(this,"engine").setContentTitle("Multimodal Engine · "+(running?p+"%":"本机 MCP")).setContentText(stage).setSmallIcon(android.R.drawable.ic_menu_share).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(running).setVisibility(Notification.VISIBILITY_PRIVATE);
            if(running){b.setProgress(100,p,EngineJobs.number(j,"totalUnits")==0);PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,EngineService.class).setAction("cancel"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);b.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"取消分析",stop).build());}
            return b.build();
        }
        final Runnable refresh=new Runnable(){public void run(){if(server==null)return;nm.notify(1,notification());Map<String,Object> j=EngineJobs.snapshot();String id=EngineJobs.string(j,"jobId");if(Boolean.TRUE.equals(j.get("completed"))&&!id.isEmpty()&&!id.equals(notifiedJob)){notifiedJob=id;PendingIntent open=PendingIntent.getActivity(EngineService.this,2,new Intent(EngineService.this,MainActivity.class).putExtra("tasks",true),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);nm.notify(2,new Notification.Builder(EngineService.this,"completed").setContentTitle("媒体任务已结束").setContentText("打开引擎查看分析结果").setSmallIcon(android.R.drawable.ic_menu_share).setContentIntent(open).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build());}h.postDelayed(this,1500);}};
        @Override public void onCreate(){
            super.onCreate();EngineJobs.init(this);nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("engine","本机 MCP 与分析任务",NotificationManager.IMPORTANCE_LOW));nm.createNotificationChannel(new NotificationChannel("completed","分析完成提醒",NotificationManager.IMPORTANCE_DEFAULT));Map<String,Object> initial=EngineJobs.snapshot();if(Boolean.TRUE.equals(initial.get("completed")))notifiedJob=EngineJobs.string(initial,"jobId");startForeground(1,notification());
            try{server=new McpServer(PORT,token(this),MainActivity::status,MainActivity::result,MainActivity::taskStatus,MainActivity::cancelRemote);serviceState="运行中";h.post(refresh);}catch(Exception e){serviceState="启动失败（端口可能占用）";stopSelf();}
        }
        @Override public int onStartCommand(Intent i,int flags,int id){
            if(server==null)return START_NOT_STICKY;
            if(i!=null)try{String a=i.getAction();if("share".equals(a)&&i.getData()!=null)copyShared(i.getData());else if("analyze".equals(a)&&i.getData()!=null)EngineJobs.start(i.getData());else if("resume".equals(a))EngineJobs.resume();else if("next".equals(a))EngineJobs.next();else if("cancel".equals(a))EngineJobs.requestCancel();}
            catch(Exception e){Toast.makeText(this,"任务未启动："+e.getMessage(),Toast.LENGTH_LONG).show();}
            nm.notify(1,notification());return START_NOT_STICKY;
        }
        @Override public IBinder onBind(Intent i){return null;}
        @Override public void onDestroy(){stopped=true;imports.shutdownNow();h.removeCallbacks(refresh);EngineJobs.requestCancel();EngineJobs.revoke();if(server!=null){try{server.close();}catch(Exception ignored){}serviceState="已停止";}super.onDestroy();}
    }
    final Handler handler=new Handler(Looper.getMainLooper());
    String displayedJob="";static volatile boolean importingModel=false;String importChoice="e2b";
    LinearLayout root,host;LinearLayout[] pages=new LinearLayout[4];Button[] tabs=new Button[4];
    TextView state,taskView,modelView,detail,progressLabel;ProgressBar progress;ImageView preview;boolean alive=true;
    final Runnable refresh=new Runnable(){public void run(){if(!alive)return;state.setText(serviceState);renderCompleted();modelView.setText(ModelStore.status(MainActivity.this));handler.postDelayed(this,1200);}};
    void renderCompleted(){
        Map<String,Object> j=EngineJobs.snapshot();
        taskView.setText(EngineJobs.string(j,"taskState"));int p=(int)EngineJobs.number(j,"progressPercent");
        progress.setIndeterminate(EngineJobs.running()&&EngineJobs.number(j,"totalUnits")==0);progress.setProgress(p);
        Object eta=j.get("etaSeconds");progressLabel.setText(p+"% · "+EngineJobs.number(j,"completedUnits")+"/"+EngineJobs.number(j,"totalUnits")+" 单元 · 已用 "+EngineJobs.number(j,"elapsedSeconds")+" 秒"+(eta==null?"":" · 估计剩余 "+eta+" 秒")+"\n排队 "+EngineJobs.number(j,"queuedCount")+" · Java heap "+EngineJobs.number(j,"javaHeapUsedMiB")+" MiB · Native heap "+EngineJobs.number(j,"nativeHeapMiB")+" MiB");
        String current=Boolean.TRUE.equals(j.get("completed"))?EngineJobs.string(j,"jobId"):"";
        if(Objects.equals(displayedJob,current))return;displayedJob=current;
        if(current.isEmpty()){preview.setImageDrawable(null);detail.setText("暂无完成结果");}
        else {Map<String,Object> m=EngineJobs.completed();preview.setImageBitmap(BitmapFactory.decodeFile(EngineJobs.preview().getAbsolutePath()));String text=McpServer.json(m);detail.setText(text.length()>18000?text.substring(0,18000)+"\n（界面预览截断，完整结果可经 MCP 导出）":text);}
    }
    int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    TextView label(LinearLayout p,String text,int size,int color){TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(5),0,dp(5));p.addView(v);return v;}
    LinearLayout card(String title){LinearLayout p=new LinearLayout(this);p.setOrientation(1);p.setPadding(dp(18),dp(14),dp(18),dp(14));GradientDrawable bg=new GradientDrawable();bg.setColor(0xff222631);bg.setCornerRadius(dp(20));p.setBackground(bg);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(12));root.addView(p,lp);TextView t=label(p,title,17,0xffe7e4ff);t.setTypeface(null,Typeface.BOLD);return p;}
    Button makeButton(String text,Runnable r){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextColor(Color.WHITE);GradientDrawable d=new GradientDrawable();d.setColor(0xff414568);d.setCornerRadius(dp(12));b.setBackground(d);b.setOnClickListener(v->r.run());return b;}
    void button(LinearLayout p,String text,Runnable r){Button b=makeButton(text,r);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(48));lp.setMargins(0,dp(6),0,dp(6));p.addView(b,lp);}
    void tell(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    void page(int i){for(int j=0;j<pages.length;j++){pages[j].setVisibility(j==i?View.VISIBLE:View.GONE);tabs[j].setAlpha(j==i?1f:.58f);}}
    void copyToken(){ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);ClipData clip=ClipData.newPlainText("MCP token",token(this));if(Build.VERSION.SDK_INT>=33){PersistableBundle extras=new PersistableBundle();extras.putBoolean("android.content.extra.IS_SENSITIVE",true);clip.getDescription().setExtras(extras);}cm.setPrimaryClip(clip);tell("已复制，请仅粘贴到 Tavo 鉴权设置");}
    void startEngine(){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},5);try{startForegroundService(new Intent(this,EngineService.class));}catch(Exception e){tell("无法启动服务");}}
    void selectMedia(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"video/*","audio/*"});i.addCategory(Intent.CATEGORY_OPENABLE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,21);}
    void chooseSetting(String title,String key,String[] labels,String[] values){if(EngineJobs.running()||importingModel){tell("请先结束当前任务或导入");return;}new AlertDialog.Builder(this).setTitle(title).setItems(labels,(d,i)->{VisionSettings.set(this,key,values[i]);tell("已选择 "+labels[i]+"，仅对新任务生效");}).show();}
    void openVisionDownload(){
        String v=VisionSettings.visual(this);if("labels".equals(v)){tell("先选择 Gemma E2B 或 E4B");return;}
        String name="e2b".equals(v)?"E2B":"E4B";
        startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://huggingface.co/google/gemma-3n-"+name+"-it-litert-preview/tree/main")));
    }
    void selectModel(){
        if(EngineJobs.running()||importingModel){tell("请先结束当前任务或导入");return;}
        if(!VlmBridge.available()){tell("当前 APK 未包含视觉语言模型运行库");return;}
        String v=VisionSettings.visual(this);if("labels".equals(v)){tell("先选择 Gemma E2B 或 E4B");return;}
        importChoice=v;Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivityForResult(i,41);
    }
    void importVision(Uri uri,String choice){
        if(EngineJobs.running()||importingModel)return;importingModel=true;
        final Context app=getApplicationContext();final File dst=VlmBridge.model(app,choice),tmp=new File(dst.getParentFile(),dst.getName()+".part");
        tell("正在导入模型，请保持应用打开；完成后再启动任务");
        new Thread(()->{String message;try{
            long expected=-1;try(android.database.Cursor c=app.getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.SIZE},null,null,null)){if(c!=null&&c.moveToFirst()&&!c.isNull(0))expected=c.getLong(0);}
            if(expected>0&&dst.getParentFile().getUsableSpace()<expected+100000000L)throw new java.io.IOException("空间不足");
            long bytes=0;try(java.io.InputStream in=app.getContentResolver().openInputStream(uri);java.io.FileOutputStream out=new java.io.FileOutputStream(tmp)){
                if(in==null)throw new java.io.IOException("无法读取文件");byte[] buf=new byte[1024*1024];int n;
                while((n=in.read(buf))!=-1){bytes+=n;if(bytes>6500000000L)throw new java.io.IOException("模型超过6.5GB");out.write(buf,0,n);}out.getFD().sync();
            }
            if(bytes<100000000L||(expected>0&&bytes!=expected))throw new java.io.IOException("模型长度异常");
            android.system.Os.rename(tmp.getAbsolutePath(),dst.getAbsolutePath());
            message="模型已导入，尚未验证推理兼容性";
        }catch(Exception e){tmp.delete();message="模型导入失败："+e.getClass().getSimpleName();}
        finally{importingModel=false;}final String result=message;handler.post(()->Toast.makeText(app,result,Toast.LENGTH_LONG).show());},"model-import").start();
    }
    void showHistory(){
        List<String> ids=EngineJobs.historyIds();if(ids.isEmpty()){tell("暂无历史报告（开始新任务时归档已完成任务）");return;}
        new AlertDialog.Builder(this).setTitle("历史报告 · 选择查看").setItems(ids.toArray(new String[0]),(d,i)->{String id=ids.get(i);try{
            String t=McpServer.json(EngineJobs.history(id));new AlertDialog.Builder(this).setTitle("历史报告").setMessage(t.length()>10000?t.substring(0,10000)+"\n…":t).setNegativeButton("返回",null).setPositiveButton("授权 MCP 读取",(a,b)->{try{EngineJobs.shareHistory(id);tell("已授权历史报告");}catch(Exception e){tell("历史读取失败");}}).show();
        }catch(Exception e){tell("历史读取失败");}}).show();
    }
    void cancelTask(){EngineJobs.requestCancel();renderCompleted();}
    @Override public void onCreate(Bundle b){
        super.onCreate(b);EngineJobs.init(this);getWindow().setStatusBarColor(0xff151820);getWindow().setNavigationBarColor(0xff151820);
        LinearLayout shell=new LinearLayout(this);shell.setOrientation(1);shell.setBackgroundColor(0xff151820);shell.setPadding(dp(16),dp(12),dp(16),dp(8));setContentView(shell);
        label(shell,"MULTIMODAL",12,0xffb7adff);TextView h=label(shell,"Engine  /  0.4.0",28,Color.WHITE);h.setTypeface(null,Typeface.BOLD);label(shell,"LOCAL · MCP · 手机端推理实验版",12,0xffaab1c4);
        ScrollView sc=new ScrollView(this);shell.addView(sc,new LinearLayout.LayoutParams(-1,0,1));host=new LinearLayout(this);host.setOrientation(1);sc.addView(host);
        for(int i=0;i<4;i++){pages[i]=new LinearLayout(this);pages[i].setOrientation(1);host.addView(pages[i]);}
        root=pages[0];LinearLayout c=card("本地 MCP 服务");state=label(c,serviceState,24,0xffb8efce);label(c,"http://127.0.0.1:8765/mcp",14,0xffb7adff);button(c,"启动服务",this::startEngine);button(c,"开启进度通知权限",()->{if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},5);else tell("通知权限已允许或系统版本无需申请");});button(c,"停止服务",()->stopService(new Intent(this,EngineService.class)));button(c,"复制配对令牌",this::copyToken);
        c=card("运行环境");ActivityManager.MemoryInfo mem=new ActivityManager.MemoryInfo();getSystemService(ActivityManager.class).getMemoryInfo(mem);StatFs fs=new StatFs(getFilesDir().getAbsolutePath());label(c,"RAM "+String.format(Locale.ROOT,"%.1f",mem.totalMem/1073741824.0)+" GiB · CPU "+Runtime.getRuntime().availableProcessors()+" 核",16,Color.WHITE);label(c,"当前可用 RAM "+String.format(Locale.ROOT,"%.1f",mem.availMem/1073741824.0)+" GiB\n内部存储可用 "+String.format(Locale.ROOT,"%.1f",fs.getAvailableBytes()/1073741824.0)+" GiB\n"+Build.MANUFACTURER+" "+Build.MODEL+"\nABI "+String.join(", ",Build.SUPPORTED_ABIS),13,0xffaab1c4);button(c,"进入媒体任务",()->page(2));
        root=pages[1];c=card("内置视觉模型");label(c,"ML Kit 图像标签 · 随 APK 安装",16,Color.WHITE);label(c,"本机推理常见物体与场景类别，不是 Qwen 或 SmolVLM，不生成详细画面描述。任务完成后可检查真实输出。",13,0xffaab1c4);
        c=card("Whisper 多语言模型");label(c,WhisperBridge.AVAILABLE?"原生运行库已加载（待模型及实测）":"此 APK 不含 Whisper 原生库",15,Color.WHITE);modelView=label(c,ModelStore.status(this),14,0xffaab1c4);button(c,"切换 Base / Small",()->{if(EngineJobs.running()){tell("分析期间不可切换模型");return;}new AlertDialog.Builder(this).setTitle("选择语音模型").setItems(new String[]{"Base · 148 MB","Small · 488 MB（较慢、内存较高）"},(d,i)->{ModelStore.select(this,i==0?ModelStore.Model.BASE:ModelStore.Model.SMALL);modelView.setText(ModelStore.status(this));}).show();});button(c,"下载模型权重（Wi-Fi）",()->{if(!WhisperBridge.AVAILABLE){tell("请先安装带 Whisper 的实验 APK，避免无效下载");return;}new AlertDialog.Builder(this).setTitle("下载所选模型权重？").setMessage("仅从指定 Hugging Face 模型仓库下载；系统管理网络重试，使用前验证 SHA-256。").setNegativeButton("返回",null).setPositiveButton("下载",(d,w)->{try{ModelStore.download(this);tell("下载任务已提交");}catch(Exception e){tell(e.getMessage());}}).show();});
        button(c,"取消下载 / 删除权重",()->new AlertDialog.Builder(this).setTitle("删除已下载权重？").setNegativeButton("返回",null).setPositiveButton("删除",(d,w)->{if(EngineJobs.running()){tell("请等当前分析完成");return;}try{ModelStore.delete(this);tell("已删除");}catch(Exception e){tell(e.getMessage());}}).show());
        c=card("视觉语言模型 · 实验");
        label(c,VlmBridge.available()?"MediaPipe 运行库已打包（尚未实测）":"普通 APK · 视觉语言模型运行库未打包",14,0xffaab1c4);
        button(c,"选择视觉模式",()->chooseSetting("视觉模式","visual",new String[]{"基础标签","Gemma 3n E2B · 3.14GB","Gemma 3n E4B · 4.41GB"},new String[]{"labels","e2b","e4b"}));
        button(c,"打开官方权重下载页面",this::openVisionDownload);button(c,"导入已下载的 .task 模型",this::selectModel);
        button(c,"删除所选视觉模型",()->{if(EngineJobs.running()||importingModel){tell("任务或导入期间不可删除");return;}String v=VisionSettings.visual(this);if("labels".equals(v)){tell("先选择模型");return;}new AlertDialog.Builder(this).setTitle("删除所选视觉模型？").setNegativeButton("返回",null).setPositiveButton("删除",(d,w)->tell(VlmBridge.model(this,v).delete()?"已删除":"未找到或删除失败")).show();});
        c=card("OCR 与语音语言");
        button(c,"OCR 字符集",()->chooseSetting("OCR 字符集","ocr",new String[]{"中文 + 拉丁","日文","韩文","拉丁","关闭"},new String[]{"zh","ja","ko","latin","off"}));
        button(c,"Whisper 识别语言",()->chooseSetting("Whisper 语言","language",new String[]{"每段自动","首次识别锁定","中文","英文","日语","韩语"},new String[]{"auto","auto-lock","zh","en","ja","ko"}));
        root=pages[2];c=card("后台媒体任务");taskView=label(c,"暂无任务",15,Color.WHITE);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);c.addView(progress,new LinearLayout.LayoutParams(-1,dp(12)));progressLabel=label(c,"0%",14,0xffb7adff);
        button(c,"选择视频 / 音频（运行中加入队列）",()->{if(importingModel){tell("模型导入中");return;}selectMedia();});
        button(c,"查看排队任务",()->{List<String> q=EngineJobs.queueLabels();new AlertDialog.Builder(this).setTitle("排队 "+q.size()+" 项").setMessage(q.isEmpty()?"暂无排队":String.join("\n",q)).setNegativeButton("返回",null).setPositiveButton("清空队列",(d,w)->{try{EngineJobs.clearQueue();tell("队列已清空");}catch(Exception e){tell("清空失败");}}).show();});
        button(c,"启动下一排队任务",()->{if(importingModel){tell("模型导入中");return;}startForegroundService(new Intent(this,EngineService.class).setAction("next"));});
        button(c,"历史报告",this::showHistory);
        button(c,"清除历史报告",()->new AlertDialog.Builder(this).setTitle("删除所有归档报告？").setNegativeButton("返回",null).setPositiveButton("删除",(d,w)->{EngineJobs.clearHistory();tell("历史已清除");}).show());
        button(c,"继续中断任务",()->{if(importingModel){tell("模型导入中");return;}if(!Boolean.TRUE.equals(EngineJobs.snapshot().get("canResume"))){tell("暂无可恢复任务");return;}try{startForegroundService(new Intent(this,EngineService.class).setAction("resume"));}catch(Exception e){tell("恢复失败");}});
        button(c,"取消任务",this::cancelTask);
        button(c,"允许 MCP 取消当前任务",()->{if(!EngineJobs.running()){tell("暂无运行中任务");return;}EngineJobs.allowCancel();tell("当前任务可由已配对客户端取消");});
        button(c,"授权 MCP 读取本次分析",()->{Map<String,Object> j=EngineJobs.snapshot();if(!Boolean.TRUE.equals(j.get("completed"))){tell("暂无完成任务");return;}String id=EngineJobs.string(j,"jobId");new AlertDialog.Builder(this).setTitle("授权共享分析文字？").setMessage("仅共享标签、转录及元数据，不共享原始媒体。").setNegativeButton("返回",null).setPositiveButton("授权",(d,w)->tell(EngineJobs.share(id)?"已授权":"任务已变化，请重新检查结果")).show();});
        button(c,"撤销共享",()->{EngineJobs.revoke();tell("已撤销");});
        button(c,"清除本机任务和检查点",()->new AlertDialog.Builder(this).setTitle("清除最近任务？").setMessage("清除分析文字、预览和恢复记录，不删除原视频。").setNegativeButton("返回",null).setPositiveButton("清除",(d,w)->{try{EngineJobs.clear();renderCompleted();}catch(Exception e){tell(e.getMessage());}}).show());
        preview=new ImageView(this);preview.setAdjustViewBounds(true);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);c.addView(preview,new LinearLayout.LayoutParams(-1,dp(220)));detail=label(c,"暂无结果",13,0xffaab1c4);detail.setTextIsSelectable(true);
        root=pages[3];c=card("隐私与说明");label(c,"服务仅监听 127.0.0.1，需要随机令牌。原始媒体和图片留在本机；授权分享后，Tavo 可将分析文字交给聊天模型。任务由前台服务执行，可切换应用及尝试锁屏运行。系统强制终止后需手动恢复。最近任务检查点保存在私有目录，最长2小时媒体；Whisper 新任务采用28秒主窗口与重叠上下文；历史仅保存12份归档，队列最多8项，取消在分段边界生效。",14,0xffaab1c4);button(c,"复制配对令牌",this::copyToken);button(c,"撤销媒体共享及远程取消",()->{EngineJobs.revoke();tell("已撤销");});
        LinearLayout nav=new LinearLayout(this);nav.setOrientation(0);String[] titles={"首页","模型","任务","设置"};for(int i=0;i<4;i++){final int n=i;tabs[i]=makeButton(titles[i],()->page(n));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(48),1);lp.setMargins(dp(2),dp(6),dp(2),0);nav.addView(tabs[i],lp);}shell.addView(nav);page(getIntent().getBooleanExtra("tasks",false)?2:0);renderCompleted();handler.post(refresh);if(b==null)handleShare(getIntent());
    }
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);handleShare(i);}
    void handleShare(Intent i){
        if(i==null||!Intent.ACTION_SEND.equals(i.getAction()))return;
        String type=i.getType();Uri uri=i.getParcelableExtra(Intent.EXTRA_STREAM);
        if(uri==null||!"content".equals(uri.getScheme())||type==null||!(type.startsWith("audio/")||type.startsWith("video/"))){tell("不支持的共享文件");return;}
        new AlertDialog.Builder(this).setTitle("接收共享媒体？").setMessage("复制到本机私有目录（最多200 MiB），开始分析或加入队列。不会上传原始媒体。").setNegativeButton("返回",null).setPositiveButton("接收",(d,w)->{
            try{Intent service=new Intent(this,EngineService.class).setAction("share").setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);service.setClipData(ClipData.newRawUri("media",uri));startForegroundService(service);page(2);}catch(Exception e){tell("共享失败，请使用系统文件选择器");}
        }).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;if(request==41){importVision(data.getData(),importChoice);return;}if(request!=21)return;
        Uri uri=data.getData();
        if(importingModel){tell("模型导入中");return;}
        try{
            getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startForegroundService(new Intent(this,EngineService.class).setAction("analyze").setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            page(2);if(EngineJobs.running())tell("已提交排队请求");
        }catch(Exception ex){tell("无法获取持久媒体权限或启动服务，请使用系统文件选择器");}
    }
    @Override protected void onDestroy(){alive=false;handler.removeCallbacks(refresh);super.onDestroy();}
}
