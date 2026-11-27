package dev.tavo.engine;

import java.nio.charset.StandardCharsets;
final class WhisperBridge {
    static final boolean AVAILABLE;
    static {boolean ok;try{System.loadLibrary("tavo_whisper");ok=true;}catch(LinkageError e){ok=false;}AVAILABLE=ok;}
    private static native byte[] infer(String model,float[] pcm,int threads,Thread worker,String language);
    static String transcribe(String model,float[] pcm,String language){
        if(!AVAILABLE)throw new IllegalStateException("Whisper 原生运行库未安装");
        byte[] result=infer(model,pcm,Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors())),Thread.currentThread(),language);
        if(result==null)throw new IllegalStateException("Whisper 推理失败或已取消");
        return new String(result,StandardCharsets.UTF_8);
    }
}
