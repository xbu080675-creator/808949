package dev.tavo.engine;
public class TimelineTest {
    static void check(boolean ok){if(!ok)throw new AssertionError();}
    public static void main(String[] args){
        for(long ms:new long[]{1,1000,120000,345110,7200000}){
            int v=Timeline.videoCount(ms),a=Timeline.audioCount(ms);check(v>0&&v<=96);check(Timeline.sample(ms,0,v)>=0);check(Timeline.sample(ms,v-1,v)<ms);check(Timeline.sample(ms,v-1,v)>=ms/2);check((a-1)*30000L<ms&&a*30000L>=ms);
            for(int i=1;i<v;i++)check(Timeline.sample(ms,i,v)>Timeline.sample(ms,i-1,v));
        }
        check(Timeline.videoCount(345110)==70);check(Timeline.sample(345110,69,70)>340000);check(Timeline.audioCount(345110)==12);
        check(Timeline.offsetTranscript("[0.000000s] hello\n[2.500000s] 你好\n",180000).equals("[180.000s] hello\n[182.500s] 你好\n"));
        check(Timeline.ownedTranscript("[0.5s] previous\n[1.0s] current\n[28.999s] end\n[29.0s] next\n",27000,28000,56000).equals("[28.000s] current\n[55.999s] end\n"));
        check(Timeline.ownedTranscript("[0.0s] start\n[1.0s] outside\n",0,0,1000).equals("[0.000s] start\n"));
        check(Timeline.progress(5,10)==50);check(Timeline.progress(15,10)==100);check(Timeline.progress(0,0)==0);check(Timeline.eta(1,10,0,1000)==null);check(Timeline.eta(5,10,0,20000)==20L);
        System.out.println("PASS timeline and overlap ownership");
    }
}
