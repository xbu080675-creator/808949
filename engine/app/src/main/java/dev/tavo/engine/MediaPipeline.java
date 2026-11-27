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
    @SuppressWarnings("unchecked")
    static synchronized Map<String,Object> analyze(Context c,Uri uri,File dir,Map<String,Object> previous,ModelStore.Model model,Checkpoint cp)throws Exception{
        Map<String,Object> m=new LinkedHashMap<>(previous);
        String language=String.valueOf(m.getOrDefault("languageMode","auto"));
        String ocrMode=String.valueOf(m.getOrDefault("ocrMode","off"));
        String visionMode=String.valueOf(m.getOrDefault("visionMode","labels"));
        int step=Boolean.TRUE.equals(m.get("overlapEnabled"))?28000:30000;
        int pad=step==28000?1000:0;
        m.put("audioStepMs",step);m.put("audioContextPaddingMs",pad);
        MediaMetadataRetriever mm=new MediaMetadataRetriever();
        List<Object> frames=m.get("labels") instanceof List?new ArrayList<>((List<Object>)m.get("labels")):new ArrayList<>();
        List<int[]> seen=new ArrayList<>();
        long ms;int vc,ac;String audio,video;
        try{
            mm.setDataSource(c,uri);
            ms=Long.parseLong(mm.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            if(ms<=0||ms>Timeline.MAX_MS)throw new IOException("duration must be between 1 ms and 2 hours");
            if(m.containsKey("durationMs")&&num(m,"durationMs")!=ms)throw new IOException("source changed; select again");
            int w=number(mm.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),0),h=number(mm.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),0);
            audio=mm.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO);video=mm.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO);
            m.put("durationMs",ms);m.put("width",w);m.put("height",h);m.put("hasAudio",audio);m.put("hasVideo",video);
            vc="yes".equals(video)&&w>0&&h>0?Timeline.videoCount(ms):0;
            ac="yes".equals(audio)?(int)((ms+step-1)/step):0;
            if(vc+ac==0)throw new IOException("no supported media tracks");
            m.put("videoSamplesPlanned",vc);m.put("audioChunksPlanned",ac);m.put("whisperModel",model.name);
            int cursor=(int)num(m,"videoCursor");int decoded=(int)num(m,"decodedFrames"),failed=(int)num(m,"labelFailures");int ocrFailed=(int)num(m,"ocrFailures"),vlmFailed=(int)num(m,"vlmFailures"),vlmCount=(int)num(m,"vlmFrames");
            cp.save(m,cursor+(int)num(m,"audioCursor"),vc+ac,"准备媒体");
            if(vc>cursor){
                double scale=Math.min(1,1280.0/Math.max(w,h));int dw=Math.max(1,(int)(w*scale)),dh=Math.max(1,(int)(h*scale));
                ImageLabeler labeler=ImageLabeling.getClient(new ImageLabelerOptions.Builder().setConfidenceThreshold(0.5f).build());
                VlmBridge vlm=null;
                try(OcrBridge ocr=new OcrBridge(ocrMode)){
                    if(!"labels".equals(visionMode)){
                        try{vlm=new VlmBridge(c,visionMode);m.put("vlmStatus","runtime initialized");}
                        catch(Exception|LinkageError e){m.put("vlmStatus","initialization failed: "+e.getClass().getSimpleName());}
                    }
                    int stride=Math.max(1,(vc+11)/12);
                    for(int i=cursor;i<vc;i++){
                        check();long t=Timeline.sample(ms,i,vc);
                        Bitmap b=Build.VERSION.SDK_INT>=27?mm.getScaledFrameAtTime(t*1000,MediaMetadataRetriever.OPTION_CLOSEST,dw,dh):mm.getFrameAtTime(t*1000,MediaMetadataRetriever.OPTION_CLOSEST);
                        if(b==null){failed++;}else{
                            if(Math.max(b.getWidth(),b.getHeight())>1280){double k=1280.0/Math.max(b.getWidth(),b.getHeight());Bitmap smaller=Bitmap.createScaledBitmap(b,Math.max(1,(int)(b.getWidth()*k)),Math.max(1,(int)(b.getHeight()*k)),true);if(smaller!=b){b.recycle();b=smaller;}}
                            int[] fp=fingerprint(b);boolean similar=false;for(int[] x:seen)if(difference(x,fp)<10){similar=true;break;}
                            // Always retain the first, last and every fourth sample; never stop at a frame quota.
                            if(!similar||i%4==0||i==vc-1||(!"labels".equals(visionMode)&&i%stride==0)){
                                seen.add(fp);decoded++;
                                File preview=new File(dir,"preview.jpg");if(!preview.exists())try(OutputStream o=new FileOutputStream(preview)){if(!b.compress(Bitmap.CompressFormat.JPEG,95,o))throw new IOException("JPEG failed");}
                                Map<String,Object> frame=McpServer.obj("sampleMs",t,"items",new ArrayList<>());
                                try{
                                    List<ImageLabel> got=new ArrayList<>(Tasks.await(labeler.process(InputImage.fromBitmap(b,0)),20,TimeUnit.SECONDS));
                                    got.sort((x,y)->Float.compare(y.getConfidence(),x.getConfidence()));List<Object> labels=new ArrayList<>();
                                    for(ImageLabel x:got.subList(0,Math.min(3,got.size())))labels.add(McpServer.obj("label",x.getText(),"confidence",Math.round(x.getConfidence()*100)/100.0));
                                    frame.put("items",labels);
                                }catch(InterruptedException e){throw e;}catch(Exception e){failed++;}
                                if(!"off".equals(ocrMode))try{String text=ocr.read(b);if(!text.isEmpty())frame.put("ocr",text);}catch(InterruptedException e){throw e;}catch(Exception e){ocrFailed++;}
                                if(vlm!=null&&(i%stride==0||i==vc-1)){
                                    try{check();String description=vlm.describe(b);check();frame.put("description",description);vlmCount++;}
                                    catch(InterruptedException e){throw e;}catch(Exception|LinkageError e){check();vlmFailed++;frame.put("vlmError",e.getClass().getSimpleName());}
                                }
                                frames.add(frame);
                            }
                        }
                        m.put("videoCursor",i+1);m.put("decodedFrames",decoded);m.put("labelFailures",failed);m.put("lastVideoSampleMs",t);m.put("labels",frames);
                        m.put("ocrFailures",ocrFailed);m.put("vlmFailures",vlmFailed);m.put("vlmFrames",vlmCount);
                        m.put("visualMethod","Sampled frames: ML Kit image labels, optional OCR, optional VLM descriptions where explicitly present; not continuous video understanding");
                        cp.save(m,i+1,vc+ac,"视觉扫描 "+(i+1)+"/"+vc);
                    }
                }finally{labeler.close();if(vlm!=null)vlm.close();}
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
                    m.remove("audioSkippedChunks");
                    StringBuilder transcript=new StringBuilder(m.get("transcript") instanceof String?(String)m.get("transcript"):"");
                    List<Object> segments=m.get("audioSegments") instanceof List?new ArrayList<>((List<Object>)m.get("audioSegments")):new ArrayList<>();
                    double seconds=m.get("audioAnalyzedSeconds") instanceof Number?((Number)m.get("audioAnalyzedSeconds")).doubleValue():0;
                    int failed=(int)num(m,"audioFailedChunks");
                    for(int i=cursor;i<ac;i++){
                        check();long from=i*(long)step,to=Math.min(ms,from+step),decodeFrom=Math.max(0,from-pad),decodeTo=Math.min(ms,to+pad);
                        cp.save(m,vc+i,vc+ac,"Whisper 分段 "+(i+1)+"/"+ac);
                        try{
                            float[] pcm=decodeAudio(c,uri,decodeFrom,decodeTo);
                            if(pcm.length==0)throw new IOException("no PCM");
                            String useLanguage="auto-lock".equals(language)?String.valueOf(m.getOrDefault("lockedLanguage","auto")):language;
                            String raw=WhisperBridge.transcribe(ModelStore.file(c,model).getAbsolutePath(),pcm,useLanguage);
                            if(raw.startsWith("@lang=")){
                                int nl=raw.indexOf('\n');if(nl>0){String detected=raw.substring(6,nl);
                                    if("auto-lock".equals(language)&&!m.containsKey("lockedLanguage")&&detected.matches("[a-z]{2,3}")&&raw.substring(nl+1).matches("(?s).*\\[\\d+.*"))m.put("lockedLanguage",detected);
                                    raw=raw.substring(nl+1);
                                }
                            }
                            String part=Timeline.ownedTranscript(raw,decodeFrom,from,to);
                            check();if(transcript.length()+part.length()>400000)throw new IOException("transcript report limit");
                            transcript.append(part);double uniqueSeconds=Math.min((to-from)/1000.0,Math.max(0,pcm.length/16000.0-(from-decodeFrom)/1000.0));seconds+=uniqueSeconds;
                            segments.add(McpServer.obj("fromMs",from,"toMs",to,"decodedSeconds",pcm.length/16000.0,"uniqueSecondsEstimate",uniqueSeconds,"decodeFromMs",decodeFrom,"decodeToMs",decodeTo,"language",useLanguage,"status","transcribed"));
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
