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
    static final int LIMIT_SECONDS=30;
    static void check()throws InterruptedException{if(Thread.currentThread().isInterrupted())throw new InterruptedException();}
    static int number(String x,int fallback){try{return Integer.parseInt(x);}catch(Exception e){return fallback;}}
    static int[] fingerprint(Bitmap b){int[] a=new int[64];for(int i=0;i<64;i++){int p=b.getPixel((i%8)*(b.getWidth()-1)/7,(i/8)*(b.getHeight()-1)/7);a[i]=(77*((p>>16)&255)+150*((p>>8)&255)+29*(p&255))>>8;}return a;}
    static int difference(int[] a,int[] b){int d=0;for(int i=0;i<64;i++)d+=Math.abs(a[i]-b[i]);return d/64;}
    interface Checkpoint {void save(Map<String,Object> media,int done,int total,String stage)throws Exception;}
    static long num(Map<String,Object> m,String k){Object x=m.get(k);return x instanceof Number?((Number)x).longValue():0;}
    static int videoCount(long ms){return (int)Math.max(1,Math.min(96,(ms+4999)/5000));}
    static int audioCount(long ms){return (int)((ms+29999)/30000);}
    static long sample(long ms,int i,int count){return (long)((i+.5)*ms/count);}
    static String offsetTranscript(String text,long startMs){
        StringBuilder out=new StringBuilder();java.util.regex.Pattern pattern=java.util.regex.Pattern.compile("^\\[(\\d+(?:\\.\\d+)?)s\\](.*)$");
        for(String line:text.split("\\n")){java.util.regex.Matcher a=pattern.matcher(line);if(a.matches())out.append('[').append(String.format(Locale.ROOT,"%.3f",startMs/1000.0+Double.parseDouble(a.group(1)))).append("s]").append(a.group(2)).append('\n');}
        return out.toString();
    }
    @SuppressWarnings("unchecked")
    static synchronized Map<String,Object> analyze(Context c,Uri uri,File dir,Map<String,Object> previous,ModelStore.Model model,Checkpoint cp)throws Exception{
        Map<String,Object> m=new LinkedHashMap<>(previous);
        MediaMetadataRetriever mm=new MediaMetadataRetriever();
        List<Object> frames=m.get("labels") instanceof List?new ArrayList<>((List<Object>)m.get("labels")):new ArrayList<>();
        List<int[]> seen=new ArrayList<>();
        long ms;int vc,ac;String audio,video;
        try{
            mm.setDataSource(c,uri);
            ms=Long.parseLong(mm.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            if(ms<=0||ms>7200000)throw new IOException("duration must be between 1 ms and 2 hours");
            if(m.containsKey("durationMs")&&num(m,"durationMs")!=ms)throw new IOException("source changed; select again");
            int w=number(mm.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),0),h=number(mm.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),0);
            audio=mm.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO);video=mm.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO);
            m.put("durationMs",ms);m.put("width",w);m.put("height",h);m.put("hasAudio",audio);m.put("hasVideo",video);
            vc="yes".equals(video)&&w>0&&h>0?videoCount(ms):0;
            ac="yes".equals(audio)?audioCount(ms):0;
            if(vc+ac==0)throw new IOException("no supported media tracks");
            m.put("videoSamplesPlanned",vc);m.put("audioChunksPlanned",ac);m.put("whisperModel",model.name);
            int cursor=(int)num(m,"videoCursor");int decoded=(int)num(m,"decodedFrames"),failed=(int)num(m,"labelFailures");
            cp.save(m,cursor+(int)num(m,"audioCursor"),vc+ac,"准备媒体");
            if(vc>cursor){
                double scale=Math.min(1,1280.0/Math.max(w,h));int dw=Math.max(1,(int)(w*scale)),dh=Math.max(1,(int)(h*scale));
                ImageLabeler labeler=ImageLabeling.getClient(new ImageLabelerOptions.Builder().setConfidenceThreshold(0.5f).build());
                try{
                    for(int i=cursor;i<vc;i++){
                        check();long t=sample(ms,i,vc);
                        Bitmap b=Build.VERSION.SDK_INT>=27?mm.getScaledFrameAtTime(t*1000,MediaMetadataRetriever.OPTION_CLOSEST,dw,dh):mm.getFrameAtTime(t*1000,MediaMetadataRetriever.OPTION_CLOSEST);
                        if(b==null){failed++;}else{
                            if(Math.max(b.getWidth(),b.getHeight())>1280){double k=1280.0/Math.max(b.getWidth(),b.getHeight());Bitmap smaller=Bitmap.createScaledBitmap(b,Math.max(1,(int)(b.getWidth()*k)),Math.max(1,(int)(b.getHeight()*k)),true);if(smaller!=b){b.recycle();b=smaller;}}
                            int[] fp=fingerprint(b);boolean similar=false;for(int[] x:seen)if(difference(x,fp)<10){similar=true;break;}
                            // Always retain the first, last and every fourth sample; never stop at a frame quota.
                            if(!similar||i%4==0||i==vc-1){
                                seen.add(fp);decoded++;
                                File preview=new File(dir,"preview.jpg");if(!preview.exists())try(OutputStream o=new FileOutputStream(preview)){if(!b.compress(Bitmap.CompressFormat.JPEG,95,o))throw new IOException("JPEG failed");}
                                try{
                                    List<ImageLabel> got=new ArrayList<>(Tasks.await(labeler.process(InputImage.fromBitmap(b,0)),20,TimeUnit.SECONDS));
                                    got.sort((x,y)->Float.compare(y.getConfidence(),x.getConfidence()));List<Object> labels=new ArrayList<>();
                                    for(ImageLabel x:got.subList(0,Math.min(3,got.size())))labels.add(McpServer.obj("label",x.getText(),"confidence",Math.round(x.getConfidence()*100)/100.0));
                                    frames.add(McpServer.obj("sampleMs",t,"items",labels));
                                }catch(InterruptedException e){throw e;}catch(Exception e){failed++;}
                            }
                        }
                        m.put("videoCursor",i+1);m.put("decodedFrames",decoded);m.put("labelFailures",failed);m.put("lastVideoSampleMs",t);m.put("labels",frames);
                        m.put("visualMethod","ML Kit on-device image labels; approximate timestamps; full timeline sampling, not continuous video understanding or VLM");
                        cp.save(m,i+1,vc+ac,"视觉扫描 "+(i+1)+"/"+vc);
                    }
                }finally{labeler.close();}
            }
        }finally{try{mm.release();}catch(Exception ignored){}}
        check();
        if(ac>0){
            int cursor=(int)num(m,"audioCursor");
            if(cursor<ac){
                if(!WhisperBridge.AVAILABLE||!ModelStore.verified(c,model)){
                    m.put("audioStatus",!WhisperBridge.AVAILABLE?"runtime unavailable":"Whisper weight missing or checksum mismatch");
                    m.put("audioSkippedChunks",ac-cursor);cp.save(m,vc+cursor,vc+ac,"音频未处理：运行库或权重不可用");
                }else{
                    StringBuilder transcript=new StringBuilder(m.get("transcript") instanceof String?(String)m.get("transcript"):"");
                    List<Object> segments=m.get("audioSegments") instanceof List?new ArrayList<>((List<Object>)m.get("audioSegments")):new ArrayList<>();
                    double seconds=m.get("audioAnalyzedSeconds") instanceof Number?((Number)m.get("audioAnalyzedSeconds")).doubleValue():0;
                    int failed=(int)num(m,"audioFailedChunks");
                    for(int i=cursor;i<ac;i++){
                        check();long from=i*30000L,to=Math.min(ms,from+30000);
                        cp.save(m,vc+i,vc+ac,"Whisper 分段 "+(i+1)+"/"+ac);
                        try{
                            float[] pcm=decodeAudio(c,uri,from,to);
                            if(pcm.length==0)throw new IOException("no PCM");
                            String part=offsetTranscript(WhisperBridge.transcribe(ModelStore.file(c,model).getAbsolutePath(),pcm),from);
                            check();if(transcript.length()+part.length()>400000)throw new IOException("transcript report limit");
                            transcript.append(part);seconds+=pcm.length/16000.0;
                            segments.add(McpServer.obj("fromMs",from,"toMs",to,"decodedSeconds",pcm.length/16000.0,"status","transcribed"));
                        }catch(InterruptedException e){throw e;}catch(Exception|LinkageError e){check();failed++;segments.add(McpServer.obj("fromMs",from,"toMs",to,"status","failed","reason",e.getMessage()==null?e.getClass().getSimpleName():e.getMessage().substring(0,Math.min(150,e.getMessage().length()))));}
                        m.put("audioCursor",i+1);m.put("audioFailedChunks",failed);m.put("audioSegments",segments);m.put("audioAnalyzedSeconds",seconds);m.put("transcript",transcript.toString());m.put("audioStatus",failed==0?"transcribed processed chunks":"partial; inspect audioSegments");
                        cp.save(m,vc+i+1,vc+ac,"音频分段 "+(i+1)+"/"+ac);
                    }
                }
            }
        }
        return m;
    }
    static float[] decodeAudio(Context c,Uri uri,long fromMs,long toMs)throws Exception{
        MediaExtractor ex=new MediaExtractor();MediaCodec codec=null;boolean started=false;
        float[] out=new float[(int)Math.min(LIMIT_SECONDS*16000L,(toMs-fromMs)*16)];int n=0;
        try{
            ex.setDataSource(c,uri,null);MediaFormat fmt=null;String mime=null;
            for(int i=0;i<ex.getTrackCount();i++){MediaFormat f=ex.getTrackFormat(i);String t=f.getString(MediaFormat.KEY_MIME);if(t!=null&&t.startsWith("audio/")){ex.selectTrack(i);fmt=f;mime=t;break;}}
            if(fmt==null)return new float[0];
            if(fromMs>0)ex.seekTo(fromMs*1000,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            int rate=fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE),channels=fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT),encoding=AudioFormat.ENCODING_PCM_16BIT;
            codec=MediaCodec.createDecoderByType(mime);codec.configure(fmt,null,null,0);codec.start();started=true;
            boolean inputDone=false,outputDone=false;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();long last=SystemClock.elapsedRealtime();long phase=0;double sum=0;int samples=0;
            while(!outputDone&&n<out.length){
                check();if(SystemClock.elapsedRealtime()-last>30000)throw new IOException("decoder stalled");
                if(!inputDone){int j=codec.dequeueInputBuffer(10000);if(j>=0){ByteBuffer b=codec.getInputBuffer(j);if(b==null)throw new IOException("no input buffer");b.clear();int size=ex.readSampleData(b,0);
                    if(size<0||ex.getSampleTime()>=toMs*1000){codec.queueInputBuffer(j,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true;}else{codec.queueInputBuffer(j,0,size,Math.max(0,ex.getSampleTime()),0);ex.advance();}last=SystemClock.elapsedRealtime();}}
                int j=codec.dequeueOutputBuffer(info,10000);
                if(j==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){MediaFormat f=codec.getOutputFormat();rate=f.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);encoding=f.containsKey(MediaFormat.KEY_PCM_ENCODING)?f.getInteger(MediaFormat.KEY_PCM_ENCODING):AudioFormat.ENCODING_PCM_16BIT;phase=0;sum=0;samples=0;}
                else if(j>=0){try{
                    ByteBuffer b=codec.getOutputBuffer(j);if(b==null)throw new IOException("no PCM");
                    if(rate<8000||rate>192000||channels<1||channels>32||(encoding!=AudioFormat.ENCODING_PCM_16BIT&&encoding!=AudioFormat.ENCODING_PCM_FLOAT))throw new IOException("unsupported PCM format");
                    int bytes=encoding==AudioFormat.ENCODING_PCM_FLOAT?4:2;b.order(ByteOrder.LITTLE_ENDIAN);b.position(info.offset);b.limit(info.offset+info.size);
                    long frameIndex=0;
                    while(b.remaining()>=channels*bytes&&n<out.length){long pts=info.presentationTimeUs+frameIndex++*1000000L/rate;float mix=0;for(int k=0;k<channels;k++)mix+=bytes==4?b.getFloat():b.getShort()/32768f;mix/=channels;if(pts<fromMs*1000)continue;if(pts>=toMs*1000){outputDone=true;break;}if(!Float.isFinite(mix))mix=0;sum+=mix;samples++;phase+=16000;while(phase>=rate&&n<out.length){out[n++]=(float)Math.max(-1,Math.min(1,samples>0?sum/samples:mix));phase-=rate;sum=0;samples=0;}}
                    outputDone=outputDone||(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                }finally{codec.releaseOutputBuffer(j,false);}last=SystemClock.elapsedRealtime();}
            }
            return Arrays.copyOf(out,n);
        }finally{if(codec!=null){if(started)try{codec.stop();}catch(Exception ignored){}try{codec.release();}catch(Exception ignored){}}ex.release();}
    }
}
