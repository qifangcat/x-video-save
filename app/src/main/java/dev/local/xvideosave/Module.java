package dev.local.xvideosave;

import android.app.*;
import android.os.*;
import android.net.Uri;
import android.content.Context;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.regex.*;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class Module implements IXposedHookLoadPackage {
    private static final String TAG="XVideoSave: ";
    private static Handler mainHandler;
    private static java.lang.ref.WeakReference<DownloadControl> currentButton=new java.lang.ref.WeakReference<>(null);
    private static java.lang.ref.WeakReference<Activity> foreground=new java.lang.ref.WeakReference<>(null);
    private static final Runnable refreshVisibility=new Runnable(){public void run(){
        Activity a=foreground.get();DownloadControl b=currentButton.get();
        if(a==null||b==null||!b.isAttachedToWindow())return;
        boolean visible=PlayerTracker.current(a)!=null;
        b.setVisibility(visible?View.VISIBLE:View.INVISIBLE);
        mainHandler.postDelayed(this,800);
    }};
    private static final Pattern ID = Pattern.compile("/(?:ext_tw_video|amplify_video|tweet_video)/([A-Za-z0-9_-]+)(?:/|\\.mp4$)");
    private static final Pattern MASTER = Pattern.compile("/(?:ext_tw_video|amplify_video|tweet_video)/\\d+/(?:pu/)?pl/[A-Za-z0-9_-]+\\.m3u8$");
    private static final Map<String,Seen> SEEN = new LinkedHashMap<>();
    private static class Seen { String id, url; long at; Seen(String i,String u) {id=i;url=u;at=System.currentTimeMillis();} }
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        if (!"com.twitter.android".equals(p.packageName) || !p.packageName.equals(p.processName)) return;
        try {
            Class<?> spec = null;
            ArrayList<String> names = new ArrayList<>(); names.add("n");
            for(char a='a';a<='z';a++) names.add(""+a);
            for(char a='a';a<='z';a++) for(char b='a';b<='z';b++) names.add(""+a+b);
            for(String name : names) {
                try {
                    Class<?> c = Class.forName("androidx.media3.datasource."+name, false, p.classLoader);
                    for(Constructor<?> ctor:c.getDeclaredConstructors()) {
                        Class<?>[] t=ctor.getParameterTypes();
                        if (t.length>=9 && t.length<=11 && t[0]==Uri.class && t[1]==long.class && t[2]==int.class && t[3]==byte[].class && t[4]==Map.class && t[5]==long.class && t[6]==long.class && t[7]==String.class && t[8]==int.class) { spec=c; break; }
                    }
                    if(spec!=null)break;
                } catch(Throwable ignored) {}
            }
            if(spec == null) { XposedBridge.log(TAG+"player request type not found"); return; }
            PlayerTracker.install(p.classLoader);
            PlaybackQuality.install(p.classLoader);
            NativePlaybackControl.install(p.classLoader);
            int count=0;
            for(Constructor<?> c:spec.getDeclaredConstructors()) {
                Class<?>[] t=c.getParameterTypes();
                if(t.length>=9 && t[0]==Uri.class && t[1]==long.class && t[2]==int.class) {
                    XposedBridge.hookMethod(c,new XC_MethodHook(){ protected void afterHookedMethod(MethodHookParam h){try {record((Uri)h.args[0]);}catch(Throwable ignored){} }}); count++;
                }
            }
            XposedBridge.log(TAG+"player hook installed: "+spec.getName()+" constructors="+count);
            XposedHelpers.findAndHookMethod(Application.class,"onCreate",new XC_MethodHook(){ protected void afterHookedMethod(MethodHookParam h){
                Application app=(Application)h.thisObject;
                mainHandler=new Handler(Looper.getMainLooper());
                PlaybackQuality.initialize(app);
                app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks(){
                    public void onActivityResumed(Activity a){ foreground=new java.lang.ref.WeakReference<>(a); }
                    public void onActivityCreated(Activity a,Bundle b){} public void onActivityStarted(Activity a){} public void onActivityPaused(Activity a){if(foreground.get()==a){foreground.clear();mainHandler.removeCallbacks(refreshVisibility);}} public void onActivityStopped(Activity a){} public void onActivitySaveInstanceState(Activity a,Bundle b){} public void onActivityDestroyed(Activity a){}
                });
            }});
        } catch(Throwable e){XposedBridge.log(TAG+"initialization failed "+e);}
    }
    private static void record(Uri uri){
        if(uri==null || !"https".equals(uri.getScheme()) || !"video.twimg.com".equalsIgnoreCase(uri.getHost()))return;
        String path=uri.getPath(); if(path==null)return;
        Matcher m=ID.matcher(path); if(!m.find())return; String id=m.group(1);
        synchronized(SEEN) {
            Seen s=SEEN.get(id);
            boolean direct=path.endsWith(".mp4") && !path.matches(".*/(?:vid|aud)/[^/]+/0/0/.*");
            if(MASTER.matcher(path).find() || direct) { if(s==null) SEEN.put(id,new Seen(id,uri.toString())); else {if(!direct || !Uri.parse(s.url).getPath().endsWith(".m3u8")) s.url=uri.toString();s.at=System.currentTimeMillis();} }
            else if(s!=null && (path.endsWith(".m4s") || path.endsWith(".ts"))) s.at=System.currentTimeMillis();
            while(SEEN.size()>30) SEEN.remove(SEEN.keySet().iterator().next());
        }
    }
    private static void addButton(Activity a){
        try {
            ViewGroup root=(ViewGroup)a.getWindow().getDecorView();
            View existing=root.findViewWithTag("xvideosave_button");
            if(existing instanceof DownloadControl){DownloadControl b=(DownloadControl)existing;currentButton=new java.lang.ref.WeakReference<>(b);b.setStatus(Downloader.status());return;}
            DownloadControl button=new DownloadControl(a);button.setTag("xvideosave_button");button.setStatus(Downloader.status());button.setVisibility(View.INVISIBLE);currentButton=new java.lang.ref.WeakReference<>(button);
            float density=a.getResources().getDisplayMetrics().density;
            int size=Math.round(48*density);
            android.widget.FrameLayout.LayoutParams lp=new android.widget.FrameLayout.LayoutParams(size,size,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
            lp.leftMargin=-Math.round(48*density);
            button.setOnClickListener(v->choose(a,QualitySelector.Quality.ORIGINAL));
            button.setOnLongClickListener(v->{showOptions(a);return true;});
            root.addView(button,lp);
        }catch(Throwable e){XposedBridge.log(TAG+"button failed "+e);}
    }
    static void setButtonStatus(String status){if(mainHandler!=null)mainHandler.post(()->{DownloadControl b=currentButton.get();if(b!=null)b.setStatus(status);NativePlaybackControl.statusChanged();});}
    static void nativeDownload(){Activity a=foreground.get();if(a!=null)choose(a,QualitySelector.Quality.ORIGINAL);}
    static void nativeOptions(){Activity a=foreground.get();if(a!=null)showOptions(a);}
    private static void showOptions(Activity a){
        String[] options={"下载原画（最高可用）","下载高清（不超过 720p）","下载标清（不超过 480p）","播放画质设置"};
        new AlertDialog.Builder(a).setTitle("视频选项").setItems(options,(dialog,index)->{
            if(index==0)choose(a,QualitySelector.Quality.ORIGINAL);
            else if(index==1)choose(a,QualitySelector.Quality.HD);
            else if(index==2)choose(a,QualitySelector.Quality.SD);
            else if(index==3)PlaybackQuality.showDialog(a);
        }).setNegativeButton("取消",null).show();
    }
    static String currentSource(Activity a, PlayerTracker.Current current){
        if(current==null)return null;
        record(current.uri);
        synchronized(SEEN){Seen selected=SEEN.get(current.id);return selected==null?null:selected.url;}
    }
    private static void choose(Activity a,QualitySelector.Quality quality){
        PlayerTracker.Current current=PlayerTracker.current(a);
        if(current==null){Toast.makeText(a,"无法确定当前视频，请播放几秒后重试",Toast.LENGTH_LONG).show();return;}
        record(current.uri); Seen selected;
        synchronized(SEEN){selected=SEEN.get(current.id);}
        if(selected==null){Toast.makeText(a,"正在识别视频，请播放几秒后重试",Toast.LENGTH_LONG).show();return;}
        XposedBridge.log(TAG+"selected current player id="+current.id.substring(Math.max(0,current.id.length()-6)));
        Downloader.start(a.getApplicationContext(),selected.url,selected.id,quality);
    }
}
