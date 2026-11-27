package dev.tavo.engine;
import android.content.Context;
import android.graphics.Bitmap;
import java.io.File;
import java.lang.reflect.*;
/** Optional MediaPipe 0.10.27 build. Missing library is explicitly reported. */
final class VlmBridge implements AutoCloseable {
    private static final String P="com.google.mediapipe.tasks.genai.llminference.";
    private Object llm;
    private Class<?> engine,session,mpImage;
    static boolean available(){try{Class.forName(P+"LlmInference",false,VlmBridge.class.getClassLoader());return true;}catch(Exception|LinkageError e){return false;}}
    static File model(Context c,String choice){if(!"e2b".equals(choice)&&!"e4b".equals(choice))throw new IllegalArgumentException("unknown model");return new File(c.getFilesDir(),"vision-"+choice+".task");}
    private static Object call(Object o,String name,Class<?>[] types,Object... args)throws Exception{return o.getClass().getMethod(name,types).invoke(o,args);}
    private static Object builder(Class<?> cls)throws Exception{return cls.getMethod("builder").invoke(null);}
    private static Object build(Object b)throws Exception{return b.getClass().getMethod("build").invoke(b);}
    private static Class<?> cls(String n)throws Exception{return Class.forName(P+n);}
    VlmBridge(Context c,String choice)throws Exception{
        File f=model(c,choice);if(!f.isFile()||f.length()<100000000L)throw new IllegalStateException("vision model not imported");
        engine=cls("LlmInference");session=cls("LlmInferenceSession");mpImage=Class.forName("com.google.mediapipe.framework.image.MPImage");
        Class<?> options=cls("LlmInference$LlmInferenceOptions");
        Object b=builder(options);
        call(b,"setModelPath",new Class[]{String.class},f.getAbsolutePath());
        call(b,"setMaxTokens",new Class[]{int.class},1024);
        call(b,"setMaxNumImages",new Class[]{int.class},1);
        llm=engine.getMethod("createFromOptions",Context.class,options).invoke(null,c,build(b));
    }
    String describe(Bitmap bitmap)throws Exception{
        Class<?> opts=cls("LlmInferenceSession$LlmInferenceSessionOptions");
        Class<?> graph=cls("GraphOptions");
        Object g=builder(graph);call(g,"setEnableVisionModality",new Class[]{boolean.class},true);
        Object b=builder(opts);call(b,"setGraphOptions",new Class[]{graph},build(g));
        call(b,"setTopK",new Class[]{int.class},10);call(b,"setTemperature",new Class[]{float.class},0.2f);
        Object s=session.getMethod("createFromOptions",engine,opts).invoke(null,llm,build(b));
        Object image=null;
        try{
            Class<?> builder=Class.forName("com.google.mediapipe.framework.image.BitmapImageBuilder");
            image=builder.getMethod("build").invoke(builder.getConstructor(Bitmap.class).newInstance(bitmap));
            session.getMethod("addQueryChunk",String.class).invoke(s,"Describe visible people, actions, objects, and scene in this single image in Chinese. Do not guess identities or unseen events. Be concise.");
            session.getMethod("addImage",mpImage).invoke(s,image);
            String text=(String)session.getMethod("generateResponse").invoke(s);
            if(text==null||text.trim().isEmpty())throw new IllegalStateException("empty VLM output");
            return text.length()>600?text.substring(0,600):text;
        }finally{try{session.getMethod("close").invoke(s);}finally{if(image!=null)try{mpImage.getMethod("close").invoke(image);}catch(Exception ignored){}}}
    }
    public void close(){if(llm!=null)try{engine.getMethod("close").invoke(llm);}catch(Exception ignored){}finally{llm=null;}}
}
