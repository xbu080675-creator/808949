#include <jni.h>
#include <whisper.h>
#include <string>
#include <vector>
#include <algorithm>
#include <memory>
extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_tavo_engine_WhisperBridge_infer(JNIEnv *env,jclass,jstring path,jfloatArray audio,jint threads,jobject worker){
    if(!path||!audio||!worker)return nullptr;
    auto n=env->GetArrayLength(audio);if(n<=0||n>180*16000)return nullptr;
    const char *p=env->GetStringUTFChars(path,nullptr);if(!p)return nullptr;
    auto cp=whisper_context_default_params();cp.use_gpu=false;
    std::unique_ptr<whisper_context,decltype(&whisper_free)> ctx(whisper_init_from_file_with_params(p,cp),whisper_free);
    env->ReleaseStringUTFChars(path,p);if(!ctx)return nullptr;
    jclass tc=env->GetObjectClass(worker);jmethodID interrupted=env->GetMethodID(tc,"isInterrupted","()Z");if(!interrupted)return nullptr;
    std::vector<float> chunk(30*16000);std::string text;
    auto wp=whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.n_threads=std::max(1,std::min(4,(int)threads));wp.language="auto";wp.translate=false;wp.no_context=true;
    wp.print_progress=false;wp.print_realtime=false;wp.print_timestamps=false;wp.print_special=false;
    for(jsize off=0;off<n;off+=30*16000){
        if(env->CallBooleanMethod(worker,interrupted)||env->ExceptionCheck())return nullptr;
        int len=std::min((int)(n-off),30*16000);env->GetFloatArrayRegion(audio,off,len,chunk.data());if(env->ExceptionCheck())return nullptr;
        if(whisper_full(ctx.get(),wp,chunk.data(),len)!=0)return nullptr;
        for(int i=0;i<whisper_full_n_segments(ctx.get());i++){
            auto t=off/16000+whisper_full_get_segment_t0(ctx.get(),i)/100.0;
            text+="["+std::to_string(t)+"s] ";text+=whisper_full_get_segment_text(ctx.get(),i);text+="\n";
        }
        if(text.size()>100000)return nullptr;
    }
    if(env->CallBooleanMethod(worker,interrupted)||env->ExceptionCheck())return nullptr;
    jbyteArray out=env->NewByteArray(text.size());if(out)env->SetByteArrayRegion(out,0,text.size(),reinterpret_cast<const jbyte*>(text.data()));return out;
}
