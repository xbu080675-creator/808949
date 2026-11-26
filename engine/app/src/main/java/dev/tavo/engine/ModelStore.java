package dev.tavo.engine;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import java.io.*;
import java.security.MessageDigest;
import java.util.Locale;

final class ModelStore {
    static final String NAME="ggml-base.bin";
    static final String URL="https://huggingface.co/ggerganov/whisper.cpp/resolve/0b364b566045a405be7225ee1e415a073e04da77/ggml-base.bin";
    static final long SIZE=147951465L;
    static final String SHA="60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe";
    static File file(Context c){File dir=c.getExternalFilesDir("models");if(dir==null)throw new IllegalStateException("存储不可用");return new File(dir,NAME);}
    static long id(Context c){return c.getSharedPreferences("model20",0).getLong("download",-1);}
    static DownloadManager dm(Context c){return c.getSystemService(DownloadManager.class);}
    static String status(Context c){
        try{long id=id(c);if(id>=0)try(Cursor q=dm(c).query(new DownloadManager.Query().setFilterById(id))){
            if(q!=null&&q.moveToFirst()){
                int state=q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                long done=q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                if(state==DownloadManager.STATUS_SUCCESSFUL)return file(c).length()==SIZE?"已下载 · 使用前校验 SHA-256":"文件大小异常 · 请删除重下";
                if(state==DownloadManager.STATUS_FAILED)return "下载失败 · 请删除后重试";
                return "下载中 / 等待网络 · "+Math.max(0,Math.min(100,done*100/SIZE))+"%";
            }
        }
        return file(c).length()==SIZE?"已保存 · 使用前校验 SHA-256":"尚未下载（约148 MB）";
        }catch(Exception e){return "模型存储状态不可用";}
    }
    static void download(Context c){
        if(id(c)>=0||file(c).exists())throw new IllegalStateException("已有下载记录，请先删除或等待完成");
        if(file(c).getParentFile().getUsableSpace()<SIZE+100000000L)throw new IllegalStateException("存储空间不足");
        DownloadManager.Request r=new DownloadManager.Request(Uri.parse(URL)).setTitle("Whisper Base 多语言权重").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setAllowedOverMetered(false).setAllowedOverRoaming(false);
        r.setDestinationInExternalFilesDir(c,"models",NAME);
        long id=dm(c).enqueue(r);c.getSharedPreferences("model20",0).edit().putLong("download",id).apply();
    }
    static void delete(Context c){long id=id(c);if(id>=0)dm(c).remove(id);File f=file(c);if(f.exists()&&!f.delete())throw new IllegalStateException("文件删除失败");c.getSharedPreferences("model20",0).edit().remove("download").apply();}
    static boolean verified(Context c)throws Exception{
        File f=file(c);if(f.length()!=SIZE)return false;MessageDigest d=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(f)){byte[] b=new byte[65536];int n;while((n=in.read(b))>=0){if(Thread.currentThread().isInterrupted())throw new InterruptedException();d.update(b,0,n);}}
        StringBuilder s=new StringBuilder();for(byte b:d.digest())s.append(String.format(Locale.ROOT,"%02x",b&255));return SHA.equals(s.toString());
    }
}
