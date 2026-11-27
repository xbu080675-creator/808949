package dev.tavo.engine;
import android.graphics.Bitmap;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import java.util.concurrent.TimeUnit;
final class OcrBridge implements AutoCloseable {
    final TextRecognizer client;
    OcrBridge(String mode){
        switch(mode){
            case "zh":client=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());break;
            case "ja":client=TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());break;
            case "ko":client=TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());break;
            case "latin":client=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);break;
            default:client=null;
        }
    }
    String read(Bitmap b)throws Exception{
        if(client==null)return "";
        String t=Tasks.await(client.process(InputImage.fromBitmap(b,0)),30,TimeUnit.SECONDS).getText();
        return t.length()>800?t.substring(0,800):t;
    }
    public void close(){if(client!=null)client.close();}
}
