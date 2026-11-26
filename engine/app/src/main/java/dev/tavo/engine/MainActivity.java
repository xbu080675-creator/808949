package dev.tavo.engine;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    static final int PORT=8765;static volatile String serviceState="未启动";static volatile Map<String,Object> shared=null;static volatile String taskState="暂无任务";
    static String token(Context c){android.content.SharedPreferences p=c.getSharedPreferences("engine",MODE_PRIVATE);String t=p.getString("token",null);if(t==null){t=UUID.randomUUID().toString()+UUID.randomUUID().toString();p.edit().putString("token",t).apply();}return t;}
    static Map<String,Object> status(){return McpServer.obj("engine","tavo-multimodal-engine","version","0.1.0","visionReady",false,"audioReady",false,"status",serviceState);}
    static Map<String,Object> result(){Map<String,Object> r=status();r.put("kind","metadata-only");r.put("media",shared);r.put("warning","No decoded visual content or transcription. Only user-shared metadata.");return r;}
    public static class EngineService extends Service {
        McpServer server;
        @Override public void onCreate(){super.onCreate();NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("engine","本机 MCP",NotificationManager.IMPORTANCE_LOW));PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);startForeground(1,new Notification.Builder(this,"engine").setContentTitle("Multimodal Engine").setContentText("本机 MCP 服务").setSmallIcon(android.R.drawable.ic_menu_share).setContentIntent(open).build());try{server=new McpServer(PORT,token(this),MainActivity::status,MainActivity::result);serviceState="运行中";}catch(Exception e){serviceState="启动失败（端口可能占用）";stopSelf();}}
        @Override public int onStartCommand(Intent i,int flags,int id){return START_NOT_STICKY;}
        @Override public IBinder onBind(Intent i){return null;}
        @Override public void onDestroy(){if(server!=null){try{server.close();}catch(Exception ignored){}serviceState="已停止";}shared=null;super.onDestroy();}
    }
    final Handler handler=new Handler(Looper.getMainLooper());final ExecutorService work=Executors.newSingleThreadExecutor();Future<?> task;int seq=0;Map<String,Object> local;LinearLayout root;TextView state,taskView;boolean alive=true;
    final Runnable refresh=new Runnable(){public void run(){if(!alive)return;state.setText(serviceState);taskView.setText(taskState);handler.postDelayed(this,700);}};
    int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    TextView label(LinearLayout p,String text,int size,int color){TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(5),0,dp(5));p.addView(v);return v;}
    LinearLayout card(String title){LinearLayout p=new LinearLayout(this);p.setOrientation(1);p.setPadding(dp(18),dp(14),dp(18),dp(14));GradientDrawable bg=new GradientDrawable();bg.setColor(0xff222631);bg.setCornerRadius(dp(20));p.setBackground(bg);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(12));root.addView(p,lp);TextView t=label(p,title,17,0xffe7e4ff);t.setTypeface(null,Typeface.BOLD);return p;}
    void button(LinearLayout p,String text,Runnable r){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextColor(Color.WHITE);GradientDrawable d=new GradientDrawable();d.setColor(0xff414568);d.setCornerRadius(dp(12));b.setBackground(d);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(46));lp.setMargins(0,dp(6),0,dp(6));p.addView(b,lp);b.setOnClickListener(v->r.run());}
    void tell(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(0xff151820);getWindow().setNavigationBarColor(0xff151820);ScrollView sc=new ScrollView(this);sc.setBackgroundColor(0xff151820);root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(20),dp(20),dp(20),dp(20));sc.addView(root);setContentView(sc);
        label(root,"MULTIMODAL",13,0xffb7adff);TextView h=label(root,"Engine  /  0.1.0",30,Color.WHITE);h.setTypeface(null,Typeface.BOLD);label(root,"本机连接 · 不需要 Termux · 连接验证版",13,0xffaab1c4);
        LinearLayout c=card("本地 MCP 服务");state=label(c,serviceState,24,0xffb8efce);label(c,"http://127.0.0.1:8765/mcp",14,0xffb7adff);button(c,"启动服务",()->{if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},5);try{startForegroundService(new Intent(this,EngineService.class));}catch(Exception e){tell("无法启动服务");}});button(c,"停止服务",()->stopService(new Intent(this,EngineService.class)));button(c,"复制配对令牌",()->{ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);ClipData clip=ClipData.newPlainText("MCP token",token(this));if(Build.VERSION.SDK_INT>=33){android.os.PersistableBundle extras=new android.os.PersistableBundle();extras.putBoolean("android.content.extra.IS_SENSITIVE",true);clip.getDescription().setExtras(extras);}cm.setPrimaryClip(clip);tell("已复制，请仅粘贴到 Tavo 鉴权设置");});
        c=card("本地模型");label(c,"视觉 · Qwen3-VL（计划）\n语音 · Whisper（计划）",16,Color.WHITE);label(c,"未集成推理运行时；此版本不会下载权重或伪造识别结果。",13,0xffaab1c4);
        c=card("媒体任务");taskView=label(c,taskState,14,Color.WHITE);button(c,"选择视频 / 音频（读取元数据）",()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"video/*","audio/*"});i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,21);});button(c,"取消任务",()->{seq++;if(task!=null)task.cancel(true);local=null;shared=null;taskState="已取消";});button(c,"授权 MCP 读取本次结果",()->{if(local==null){tell("暂无已完成任务");return;}shared=new LinkedHashMap<>(local);tell("已授权，仅分享元数据，不分享原始文件");});button(c,"撤销共享",()->{shared=null;tell("已撤销");});label(c,"任务不会自动注入聊天；首版不识别画面或对白。",13,0xffaab1c4);handler.post(refresh);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=21||result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();if(task!=null)task.cancel(true);final int job=++seq;local=null;shared=null;taskState="正在读取本机媒体…";task=work.submit(()->{Map<String,Object> m=McpServer.obj();MediaMetadataRetriever mm=new MediaMetadataRetriever();try{mm.setDataSource(this,uri);m.put("durationMs",mm.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));m.put("width",mm.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));m.put("height",mm.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));m.put("hasAudio",mm.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));m.put("hasVideo",mm.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO));handler.post(()->{if(alive&&job==seq){local=m;taskState="完成 · 仅元数据\n"+McpServer.json(m);}});}catch(Exception e){handler.post(()->{if(alive&&job==seq)taskState="读取失败：文件或编码不兼容";});}finally{try{mm.release();}catch(Exception ignored){}}});}
    @Override protected void onDestroy(){alive=false;handler.removeCallbacks(refresh);work.shutdownNow();super.onDestroy();}
}
