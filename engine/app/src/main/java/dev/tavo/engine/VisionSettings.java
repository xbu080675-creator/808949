package dev.tavo.engine;
import android.content.Context;
import android.content.SharedPreferences;
final class VisionSettings {
    static SharedPreferences prefs(Context c){return c.getSharedPreferences("analysis40",0);}
    static String language(Context c){return prefs(c).getString("language","auto");}
    static String ocr(Context c){return prefs(c).getString("ocr","zh");}
    static String visual(Context c){return prefs(c).getString("visual","labels");}
    static void set(Context c,String key,String value){prefs(c).edit().putString(key,value).apply();}
}