package dev.tavo.engine;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import java.io.*;
import java.security.MessageDigest;
import java.util.Locale;

final class ModelStore {
    static final String REV="0b364b566045a405be7225ee1e415a073e04da77";
    enum Model {
        BASE("base",147951465L,"60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe"),
        SMALL("small",487601967L,"1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b");
        final String name,sha; final long size;
        Model(String name,long size,String sha){this.name=name;this.size=size;this.sha=sha;}
        String filename(){return "ggml-"+name+".bin";}
    }
    static SharedPreferences prefs(Context c){return c.getSharedPreferences("model20",0);}
    static Model selected(Context c){return "small".equals(prefs(c).getString("selected","base"))?Model.SMALL:Model.BASE;}
    static void select(Context c,Model m){prefs(c).edit().putString("selected",m.name).apply();}
    static File file(Context c,Model m){File dir=c.getExternalFilesDir("models");if(dir==null)throw new IllegalStateException("存储不可用");return new File(dir,m.filename());}
    static String key(Model m){return m==Model.BASE?"download":"download-small";}
    static long id(Context c,Model m){return prefs(c).getLong(key(m),-1);}
    static DownloadManager dm(Context c){return c.getSystemService(DownloadManager.class);}
    static String status(Context c){
        Model m=selected(c);
        try{long id=id(c,m);if(id>=0)try(Cursor q=dm(c).query(new DownloadManager.Query().setFilterById(id))){
            if(q!=null&&q.moveToFirst()){
                int state=q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                long done=q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                if(state==DownloadManager.STATUS_SUCCESSFUL)return m.name+" · "+(file(c,m).length()==m.size?"已下载 · 使用前校验":"大小异常 · 请删除重下");
                if(state==DownloadManager.STATUS_FAILED)return m.name+" · 下载失败 · 请删除后重试";
                return m.name+" · 下载中 / 等待网络 · "+Math.max(0,Math.min(100,done*100/m.size))+"%";
            }
        }
        return m.name+" · "+(file(c,m).length()==m.size?"已保存 · 使用前校验":"尚未下载（约"+Math.round(m.size/1000000.0)+" MB）");
        }catch(Exception e){return "模型存储状态不可用";}
    }
    static void download(Context c){
        Model m=selected(c);
        if(id(c,m)>=0||file(c,m).exists())throw new IllegalStateException("已有下载记录，请先删除或等待完成");
        if(file(c,m).getParentFile().getUsableSpace()<m.size+100000000L)throw new IllegalStateException("存储空间不足");
        DownloadManager.Request r=new DownloadManager.Request(Uri.parse("https://huggingface.co/ggerganov/whisper.cpp/resolve/"+REV+"/"+m.filename())).setTitle("Whisper "+m.name+" 多语言权重").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setAllowedOverMetered(false).setAllowedOverRoaming(false);
        r.setDestinationInExternalFilesDir(c,"models",m.filename());
        long id=dm(c).enqueue(r);prefs(c).edit().putLong(key(m),id).apply();
    }
    static void delete(Context c){Model m=selected(c);long id=id(c,m);if(id>=0)dm(c).remove(id);File f=file(c,m);if(f.exists()&&!f.delete())throw new IllegalStateException("文件删除失败");prefs(c).edit().remove(key(m)).apply();}
    static boolean verified(Context c,Model m)throws Exception{
        File f=file(c,m);if(f.length()!=m.size)return false;MessageDigest d=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(f)){byte[] b=new byte[65536];int n;while((n=in.read(b))>=0){if(Thread.currentThread().isInterrupted())throw new InterruptedException();d.update(b,0,n);}}
        StringBuilder s=new StringBuilder();for(byte b:d.digest())s.append(String.format(Locale.ROOT,"%02x",b&255));return m.sha.equals(s.toString());
    }
}
