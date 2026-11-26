package dev.tavo.engine;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends Activity {
    static final int PORT=8765;
    static volatile String serviceState="未启动",taskState="暂无任务",phase="idle";
    static volatile Map<String,Object> shared=null;
    static final AtomicReference<Runnable> remoteCancel=new AtomicReference<>();
    static String token(Context c){SharedPreferences p=c.getSharedPreferences("engine",MODE_PRIVATE);String t=p.getString("token",null);if(t==null){t=UUID.randomUUID().toString()+UUID.randomUUID().toString();p.edit().putString("token",t).apply();}return t;}
    static Map<String,Object> status(){return McpServer.obj("engine","tavo-multimodal-engine","version","0.2.0","visionReady",false,"visualLabelingBundled",true,"audioReady",false,"whisperRuntimeLoaded",WhisperBridge.AVAILABLE,"status",serviceState);}
    static Map<String,Object> result(){
        Map<String,Object> media=shared;
        Map<String,Object> r=status();
        r.put("kind",media!=null&&(media.containsKey("labels")||media.containsKey("transcript"))?"analysis":"metadata-only");
        r.put("media",media);r.put("warning","Explicitly shared data only. Labels are approximate, not visual-language descriptions. Missing transcript means unknown audio.");return r;
    }
    static Map<String,Object> taskStatus(){Map<String,Object> r=status();r.put("phase",phase);r.put("taskState",taskState);r.put("cancelAuthorized",remoteCancel.get()!=null);return r;}
    static Map<String,Object> cancelRemote(){Runnable r=remoteCancel.getAndSet(null);if(r!=null)r.run();Map<String,Object> m=status();m.put("cancelRequested",r!=null);return m;}
    public static class EngineService extends Service {
        McpServer server;
        @Override public void onCreate(){super.onCreate();NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("engine","本机 MCP",NotificationManager.IMPORTANCE_LOW));PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);startForeground(1,new Notification.Builder(this,"engine").setContentTitle("Multimodal Engine").setContentText("本机 MCP 服务").setSmallIcon(android.R.drawable.ic_menu_share).setContentIntent(open).build());try{server=new McpServer(PORT,token(this),MainActivity::status,MainActivity::result,MainActivity::taskStatus,MainActivity::cancelRemote);serviceState="运行中";}catch(Exception e){serviceState="启动失败（端口可能占用）";stopSelf();}}
        @Override public int onStartCommand(Intent i,int flags,int id){return START_NOT_STICKY;}
        @Override public IBinder onBind(Intent i){return null;}
        @Override public void onDestroy(){if(server!=null){try{server.close();}catch(Exception ignored){}serviceState="已停止";}shared=null;remoteCancel.set(null);super.onDestroy();}
    }
    final Handler handler=new Handler(Looper.getMainLooper());
    final ExecutorService work=Executors.newSingleThreadExecutor();
    Future<?> task;int seq=0;Map<String,Object> local;
    LinearLayout root,host;LinearLayout[] pages=new LinearLayout[4];Button[] tabs=new Button[4];
    TextView state,taskView,modelView,detail;ImageView preview;boolean alive=true;
    final Runnable refresh=new Runnable(){public void run(){if(!alive)return;state.setText(serviceState);taskView.setText(taskState);modelView.setText(ModelStore.status(MainActivity.this));handler.postDelayed(this,1200);}};
    int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    TextView label(LinearLayout p,String text,int size,int color){TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(5),0,dp(5));p.addView(v);return v;}
    LinearLayout card(String title){LinearLayout p=new LinearLayout(this);p.setOrientation(1);p.setPadding(dp(18),dp(14),dp(18),dp(14));GradientDrawable bg=new GradientDrawable();bg.setColor(0xff222631);bg.setCornerRadius(dp(20));p.setBackground(bg);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(12));root.addView(p,lp);TextView t=label(p,title,17,0xffe7e4ff);t.setTypeface(null,Typeface.BOLD);return p;}
    Button makeButton(String text,Runnable r){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextColor(Color.WHITE);GradientDrawable d=new GradientDrawable();d.setColor(0xff414568);d.setCornerRadius(dp(12));b.setBackground(d);b.setOnClickListener(v->r.run());return b;}
    void button(LinearLayout p,String text,Runnable r){Button b=makeButton(text,r);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(48));lp.setMargins(0,dp(6),0,dp(6));p.addView(b,lp);}
    void tell(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    void page(int i){for(int j=0;j<pages.length;j++){pages[j].setVisibility(j==i?View.VISIBLE:View.GONE);tabs[j].setAlpha(j==i?1f:.58f);}}
    void copyToken(){ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);ClipData clip=ClipData.newPlainText("MCP token",token(this));if(Build.VERSION.SDK_INT>=33){PersistableBundle extras=new PersistableBundle();extras.putBoolean("android.content.extra.IS_SENSITIVE",true);clip.getDescription().setExtras(extras);}cm.setPrimaryClip(clip);tell("已复制，请仅粘贴到 Tavo 鉴权设置");}
    void startEngine(){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},5);try{startForegroundService(new Intent(this,EngineService.class));}catch(Exception e){tell("无法启动服务");}}
    void selectMedia(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"video/*","audio/*"});i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,21);}
    void cancelTask(){seq++;remoteCancel.set(null);if(task!=null)task.cancel(true);local=null;shared=null;phase="cancelled";taskState="已取消；原生推理可能在当前30秒分段结束后退出";detail.setText("");preview.setImageDrawable(null);}
    @Override public void onCreate(Bundle b){
        super.onCreate(b);getWindow().setStatusBarColor(0xff151820);getWindow().setNavigationBarColor(0xff151820);
        LinearLayout shell=new LinearLayout(this);shell.setOrientation(1);shell.setBackgroundColor(0xff151820);shell.setPadding(dp(16),dp(12),dp(16),dp(8));setContentView(shell);
        label(shell,"MULTIMODAL",12,0xffb7adff);TextView h=label(shell,"Engine  /  0.2.0",28,Color.WHITE);h.setTypeface(null,Typeface.BOLD);label(shell,"LOCAL · MCP · 手机端推理实验版",12,0xffaab1c4);
        ScrollView sc=new ScrollView(this);shell.addView(sc,new LinearLayout.LayoutParams(-1,0,1));host=new LinearLayout(this);host.setOrientation(1);sc.addView(host);
        for(int i=0;i<4;i++){pages[i]=new LinearLayout(this);pages[i].setOrientation(1);host.addView(pages[i]);}
        root=pages[0];LinearLayout c=card("本地 MCP 服务");state=label(c,serviceState,24,0xffb8efce);label(c,"http://127.0.0.1:8765/mcp",14,0xffb7adff);button(c,"启动服务",this::startEngine);button(c,"停止服务",()->stopService(new Intent(this,EngineService.class)));button(c,"复制配对令牌",this::copyToken);
        c=card("运行环境");ActivityManager.MemoryInfo mem=new ActivityManager.MemoryInfo();getSystemService(ActivityManager.class).getMemoryInfo(mem);StatFs fs=new StatFs(getFilesDir().getAbsolutePath());label(c,"RAM "+String.format(Locale.ROOT,"%.1f",mem.totalMem/1073741824.0)+" GiB · CPU "+Runtime.getRuntime().availableProcessors()+" 核",16,Color.WHITE);label(c,"当前可用 RAM "+String.format(Locale.ROOT,"%.1f",mem.availMem/1073741824.0)+" GiB\n内部存储可用 "+String.format(Locale.ROOT,"%.1f",fs.getAvailableBytes()/1073741824.0)+" GiB\n"+Build.MANUFACTURER+" "+Build.MODEL+"\nABI "+String.join(", ",Build.SUPPORTED_ABIS),13,0xffaab1c4);button(c,"进入媒体任务",()->page(2));
        root=pages[1];c=card("内置视觉模型");label(c,"ML Kit 图像标签 · 随 APK 安装",16,Color.WHITE);label(c,"本机推理常见物体与场景类别，不是 Qwen 或 SmolVLM，不生成详细画面描述。任务完成后可检查真实输出。",13,0xffaab1c4);
        c=card("Whisper Base 多语言");label(c,WhisperBridge.AVAILABLE?"原生运行库已加载（待模型及实测）":"此 APK 不含 Whisper 原生库",15,Color.WHITE);modelView=label(c,ModelStore.status(this),14,0xffaab1c4);button(c,"下载模型权重（Wi-Fi）",()->{if(!WhisperBridge.AVAILABLE){tell("请先安装带 Whisper 的实验 APK，避免无效下载");return;}new AlertDialog.Builder(this).setTitle("下载约148 MB权重？").setMessage("仅从指定 Hugging Face 模型仓库下载；系统管理网络重试，使用前验证 SHA-256。").setNegativeButton("返回",null).setPositiveButton("下载",(d,w)->{try{ModelStore.download(this);tell("下载任务已提交");}catch(Exception e){tell(e.getMessage());}}).show();});
        button(c,"取消下载 / 删除权重",()->new AlertDialog.Builder(this).setTitle("删除已下载权重？").setNegativeButton("返回",null).setPositiveButton("删除",(d,w)->{if(task!=null&&!task.isDone()){tell("请等当前分析完成");return;}try{ModelStore.delete(this);tell("已删除");}catch(Exception e){tell(e.getMessage());}}).show());
        c=card("后续视觉语言模型");label(c,"SmolVLM2 / Qwen3-VL：待集成 llama.cpp 多模态运行时。此版不下载无法使用的模型。",14,0xffaab1c4);
        root=pages[2];c=card("媒体任务");taskView=label(c,taskState,15,Color.WHITE);button(c,"选择视频 / 音频并分析",this::selectMedia);button(c,"取消任务",this::cancelTask);button(c,"允许 MCP 取消当前任务",()->{if(!"running".equals(phase)){tell("暂无运行中任务");return;}final int job=seq;remoteCancel.set(()->handler.post(()->{if(alive&&seq==job&&"running".equals(phase))cancelTask();}));tell("当前任务可由已配对客户端取消");});
        button(c,"授权 MCP 读取本次分析",()->{if(local==null){tell("暂无完成任务");return;}new AlertDialog.Builder(this).setTitle("授权共享分析文字？").setMessage("只共享标签、转录及元数据；不共享原始文件或抽帧图片。").setNegativeButton("返回",null).setPositiveButton("授权",(d,w)->{if(local!=null){shared=new LinkedHashMap<>(local);tell("已授权");}}).show();});button(c,"撤销共享",()->{shared=null;tell("已撤销");});
        preview=new ImageView(this);preview.setAdjustViewBounds(true);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);c.addView(preview,new LinearLayout.LayoutParams(-1,dp(220)));detail=label(c,"暂无结果",13,0xffaab1c4);detail.setTextIsSelectable(true);
        root=pages[3];c=card("隐私与说明");label(c,"服务仅监听 127.0.0.1，需要随机令牌。原始媒体和图片留在本机；授权分享后，Tavo 可将分析文字交给聊天模型。任务在应用后台运行期间可继续，但关闭应用或系统回收可能中断。Whisper 最多转录前180秒，取消在分段边界生效。",14,0xffaab1c4);button(c,"复制配对令牌",this::copyToken);button(c,"撤销媒体共享及远程取消",()->{shared=null;remoteCancel.set(null);tell("已撤销");});
        LinearLayout nav=new LinearLayout(this);nav.setOrientation(0);String[] titles={"首页","模型","任务","设置"};for(int i=0;i<4;i++){final int n=i;tabs[i]=makeButton(titles[i],()->page(n));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(48),1);lp.setMargins(dp(2),dp(6),dp(2),0);nav.addView(tabs[i],lp);}shell.addView(nav);page(0);handler.post(refresh);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request!=21||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();if(task!=null)task.cancel(true);final int job=++seq;remoteCancel.set(null);local=null;shared=null;phase="running";taskState="准备本机分析";detail.setText("");preview.setImageDrawable(null);
        task=work.submit(()->{File dir=new File(getCacheDir(),"frames20-"+UUID.randomUUID());try{
            if(!dir.mkdirs())throw new IllegalStateException("cache unavailable");
            Map<String,Object> m=MediaPipeline.analyze(this,uri,dir,s->handler.post(()->{if(alive&&job==seq)taskState=s;}));
            File[] frames=dir.listFiles((d,name)->name.endsWith(".jpg"));Bitmap bitmap=null;if(frames!=null&&frames.length>0){Arrays.sort(frames);bitmap=BitmapFactory.decodeFile(frames[0].getAbsolutePath());}
            final Bitmap pic=bitmap;handler.post(()->{if(alive&&job==seq){local=m;phase="done";remoteCancel.set(null);taskState="已完成 · 请检查并授权分享";preview.setImageBitmap(pic);detail.setText(McpServer.json(m));}else if(pic!=null)pic.recycle();});
        }catch(InterruptedException ignored){}catch(Exception e){handler.post(()->{if(alive&&job==seq){phase="failed";remoteCancel.set(null);taskState="处理失败 · "+e.getClass().getSimpleName();}});}finally{File[] files=dir.listFiles();if(files!=null)for(File file:files)file.delete();dir.delete();}});
    }
    @Override protected void onDestroy(){alive=false;handler.removeCallbacks(refresh);if("running".equals(phase)){phase="cancelled";taskState="任务因界面销毁而取消";}remoteCancel.set(null);work.shutdownNow();super.onDestroy();}
}
