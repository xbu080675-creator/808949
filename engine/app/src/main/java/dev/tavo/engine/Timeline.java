package dev.tavo.engine;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Timeline {
    static final long MAX_MS=7200000L;
    static int videoCount(long ms){return (int)Math.max(1,Math.min(96,(ms+4999)/5000));}
    static int audioCount(long ms){return (int)((ms+29999)/30000);}
    static long sample(long ms,int i,int count){return (long)((i+.5)*ms/count);}
    static long progress(long done,long total){return total>0?Math.max(0,Math.min(100,100*done/total)):0;}
    static Long eta(long done,long total,long sessionDone,long spent){long current=done-sessionDone;return current>=2&&total>done&&spent>0?Math.min(86400L,(spent/current*(total-done)+999)/1000):null;}
    static String offsetTranscript(String text,long startMs){
        StringBuilder out=new StringBuilder();Pattern pattern=Pattern.compile("^\\[(\\d+(?:\\.\\d+)?)s\\](.*)$");
        for(String line:text.split("\\n")){Matcher a=pattern.matcher(line);if(a.matches())out.append('[').append(String.format(Locale.ROOT,"%.3f",startMs/1000.0+Double.parseDouble(a.group(1)))).append("s]").append(a.group(2)).append('\n');}
        return out.toString();
    }
}
