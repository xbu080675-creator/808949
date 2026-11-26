package dev.tavo.engine;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.*;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.ImageLabel;
import com.google.mlkit.vision.label.ImageLabeler;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class MediaPipeline {
    static final int LIMIT_SECONDS=180;
    static void check()throws InterruptedException{if(Thread.currentThread().isInterrupted())throw new InterruptedException();}
    static int number(String x,int fallback){try{return Integer.parseInt(x);}catch(Exception e){return fallback;}}
    static int[] fingerprint(Bitmap b){int[] a=new int[64];for(int i=0;i<64;i++){int p=b.getPixel((i%8)*(b.getWidth()-1)/7,(i/8)*(b.getHeight()-1)/7);a[i]=(77*((p>>16)&255)+150*((p>>8)&255)+29*(p&255))>>8;}return a;}
    static int difference(int[] a,int[] b){int d=0;for(int i=0;i<64;i++)d+=Math.abs(a[i]-b[i]);return d/64;}
    static Map<String,Object> analyze(Context c,Uri uri,File dir,Consumer<String> progress)throws Exception{
        Map<String,Object> m=McpServer.obj();MediaMetadataRetriever mm=new MediaMetadataRetriever();
        List<Object> frames=new ArrayList<>();List<int[]> seen=new ArrayList<>();
        try{
            mm.setDataSource(c,uri);
            String duration=mm.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long ms;try{ms=Long.parseLong(duration);}catch(Exception e){ms=0;}
            int w=number(mm.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),0),h=number(mm.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),0);
            String audio=mm.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO),video=mm.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO);
            m.put("durationMs",ms);m.put("width",w);m.put("height",h);m.put("hasAudio",audio);m.put("hasVideo",video);
            if("yes".equals(video)&&ms>0&&w>0&&h>0){
                int count=(int)Math.max(1,Math.min(48,Math.ceil(ms/5000.0)));
                double scale=Math.min(1,1280.0/Math.max(w,h));int dw=Math.max(1,(int)(w*scale)),dh=Math.max(1,(int)(h*scale));
                ImageLabeler labeler=ImageLabeling.getClient(new ImageLabelerOptions.Builder().setConfidenceThreshold(0.5f).build());
                try{
                    int decoded=0,failed=0;
                    for(int i=0;i<count&&seen.size()<24;i++){
                        check();progress.accept("扫描视频 "+(i+1)+"/"+count);
                        long t=(long)((i+.5)*ms/count);
                        Bitmap b=Build.VERSION.SDK_INT>=27?mm.getScaledFrameAtTime(t*1000,MediaMetadataRetriever.OPTION_CLOSEST,dw,dh):mm.getFrameAtTime(t*1000,MediaMetadataRetriever.OPTION_CLOSEST);
                        if(b==null)continue;
                        try{
                            if(Math.max(b.getWidth(),b.getHeight())>1280){double s=1280.0/Math.max(b.getWidth(),b.getHeight());Bitmap smaller=Bitmap.createScaledBitmap(b,Math.max(1,(int)(b.getWidth()*s)),Math.max(1,(int)(b.getHeight()*s)),true);if(smaller!=b){b.recycle();b=smaller;}}
                            int[] fp=fingerprint(b);boolean similar=false;for(int[] x:seen)if(difference(x,fp)<10){similar=true;break;}
                            if(similar&&i%8!=0)continue;
                            seen.add(fp);decoded++;
                            File out=new File(dir,"frame-"+t+".jpg");try(OutputStream f=new FileOutputStream(out)){if(!b.compress(Bitmap.CompressFormat.JPEG,95,f))throw new IOException("JPEG failed");}
                            List<Object> labels=new ArrayList<>();try{
                                List<ImageLabel> got=Tasks.await(labeler.process(InputImage.fromBitmap(b,0)),20,TimeUnit.SECONDS);
                                got.sort((x,y)->Float.compare(y.getConfidence(),x.getConfidence()));
                                for(ImageLabel x:got.subList(0,Math.min(3,got.size())))labels.add(McpServer.obj("label",x.getText(),"confidence",Math.round(x.getConfidence()*100)/100.0));
                                frames.add(McpServer.obj("sampleMs",t,"items",labels));
                            }catch(InterruptedException e){throw e;}catch(Exception e){failed++;}
                        }finally{b.recycle();}
                    }
                    m.put("decodedFrames",decoded);m.put("labelFailures",failed);
                    if(!frames.isEmpty())m.put("labels",frames);
                    m.put("visualMethod","ML Kit on-device image labels; sample times approximate; not a VLM");
                }finally{labeler.close();}
            }
        }finally{try{mm.release();}catch(Exception ignored){}}
        check();
        if("yes".equals(m.get("hasAudio"))){
            try{
                if(!WhisperBridge.AVAILABLE)m.put("audioStatus","runtime unavailable");
                else if(!ModelStore.verified(c))m.put("audioStatus","Whisper weight missing or checksum mismatch");
                else {
                    progress.accept("提取音轨并转换为 16kHz 单声道");
                    float[] pcm=decodeAudio(c,uri);
                    m.put("audioAnalyzedSeconds",pcm.length/16000.0);
                    if(pcm.length>0){check();progress.accept("Whisper 本机转录（可能较慢）");String text=WhisperBridge.transcribe(ModelStore.file(c).getAbsolutePath(),pcm);check();if(text.length()>7000){m.put("transcript",text.substring(0,7000));m.put("transcriptTruncated",true);}else m.put("transcript",text);m.put("audioStatus","transcribed; maximum first 180 seconds");}
                    else m.put("audioStatus","no decoded audio samples");
                }
            }catch(InterruptedException e){throw e;}catch(Exception|LinkageError e){check();m.put("audioStatus","decode or inference failed: "+e.getClass().getSimpleName());}
        }
        return m;
    }
    static float[] decodeAudio(Context c,Uri uri)throws Exception{
        MediaExtractor ex=new MediaExtractor();MediaCodec codec=null;boolean started=false;
        float[] out=new float[LIMIT_SECONDS*16000];int n=0;
        try{
            ex.setDataSource(c,uri,null);MediaFormat fmt=null;String mime=null;
            for(int i=0;i<ex.getTrackCount();i++){MediaFormat f=ex.getTrackFormat(i);String t=f.getString(MediaFormat.KEY_MIME);if(t!=null&&t.startsWith("audio/")){ex.selectTrack(i);fmt=f;mime=t;break;}}
            if(fmt==null)return new float[0];
            int rate=fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE),channels=fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT),encoding=AudioFormat.ENCODING_PCM_16BIT;
            codec=MediaCodec.createDecoderByType(mime);codec.configure(fmt,null,null,0);codec.start();started=true;
            boolean inputDone=false,outputDone=false;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();long last=SystemClock.elapsedRealtime();long phase=0;double sum=0;int samples=0;
            while(!outputDone&&n<out.length){
                check();if(SystemClock.elapsedRealtime()-last>30000)throw new IOException("decoder stalled");
                if(!inputDone){int j=codec.dequeueInputBuffer(10000);if(j>=0){ByteBuffer b=codec.getInputBuffer(j);if(b==null)throw new IOException("no input buffer");b.clear();int size=ex.readSampleData(b,0);
                    if(size<0){codec.queueInputBuffer(j,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true;}else{codec.queueInputBuffer(j,0,size,Math.max(0,ex.getSampleTime()),0);ex.advance();}last=SystemClock.elapsedRealtime();}}
                int j=codec.dequeueOutputBuffer(info,10000);
                if(j==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){MediaFormat f=codec.getOutputFormat();rate=f.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);encoding=f.containsKey(MediaFormat.KEY_PCM_ENCODING)?f.getInteger(MediaFormat.KEY_PCM_ENCODING):AudioFormat.ENCODING_PCM_16BIT;phase=0;sum=0;samples=0;}
                else if(j>=0){try{
                    ByteBuffer b=codec.getOutputBuffer(j);if(b==null)throw new IOException("no PCM");
                    if(rate<8000||rate>192000||channels<1||channels>32||(encoding!=AudioFormat.ENCODING_PCM_16BIT&&encoding!=AudioFormat.ENCODING_PCM_FLOAT))throw new IOException("unsupported PCM format");
                    int bytes=encoding==AudioFormat.ENCODING_PCM_FLOAT?4:2;b.order(ByteOrder.LITTLE_ENDIAN);b.position(info.offset);b.limit(info.offset+info.size);
                    while(b.remaining()>=channels*bytes&&n<out.length){float mix=0;for(int k=0;k<channels;k++)mix+=bytes==4?b.getFloat():b.getShort()/32768f;mix/=channels;if(!Float.isFinite(mix))mix=0;sum+=mix;samples++;phase+=16000;while(phase>=rate&&n<out.length){out[n++]=(float)Math.max(-1,Math.min(1,samples>0?sum/samples:mix));phase-=rate;sum=0;samples=0;}}
                    outputDone=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                }finally{codec.releaseOutputBuffer(j,false);}last=SystemClock.elapsedRealtime();}
            }
            return Arrays.copyOf(out,n);
        }finally{if(codec!=null){if(started)try{codec.stop();}catch(Exception ignored){}try{codec.release();}catch(Exception ignored){}}ex.release();}
    }
}
