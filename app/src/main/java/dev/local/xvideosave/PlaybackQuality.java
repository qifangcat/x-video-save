package dev.local.xvideosave;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.lang.reflect.*;
import java.util.*;
import de.robv.android.xposed.*;

/** X 12.41's Media3 track-selection parameters. Auto preserves the host's own policy. */
final class PlaybackQuality {
    private static final String[] LABELS={"跟随 X 自动","原画（优先最高）","高清（约 720p）","标清（约 480p）"};
    private static final Map<Object,Object> baseline=Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<Boolean> internal=new ThreadLocal<>();
    private static volatile int mode;
    private static volatile boolean available;
    private static SharedPreferences preferences;
    private static Class<?> playerClass, parameterClass, builderClass;
    private static Method setter, getter;

    static void install(ClassLoader loader) {
        try {
            playerClass=Class.forName("androidx.media3.exoplayer.j0",false,loader);
            getter=playerClass.getMethod("S");parameterClass=getter.getReturnType();
            Class<?> builder=parameterClass.getMethod("a").getReturnType();builderClass=builder;
            for(String f:new String[]{"a","b","c","d","e","f","i","j"})
                if(builder.getDeclaredField(f).getType()!=int.class)throw new IllegalStateException();
            for(String f:new String[]{"k","F","G"})
                if(builder.getDeclaredField(f).getType()!=boolean.class)throw new IllegalStateException();
            setter=playerClass.getMethod("B0",parameterClass);
            if(getter.getReturnType()!=parameterClass || parameterClass.getMethod("a").getReturnType()!=builder || builder.getMethod("b").getReturnType()!=parameterClass)throw new IllegalStateException();
            XposedBridge.hookMethod(setter,new XC_MethodHook(){protected void beforeHookedMethod(MethodHookParam p){
                if(Boolean.TRUE.equals(internal.get()) || p.args[0]==null)return;
                try {baseline.put(p.thisObject,p.args[0]);if(mode!=0)p.args[0]=modify(p.args[0],mode);}
                catch(Throwable e){XposedBridge.log("XVideoSave: playback parameters unchanged: "+e.getClass().getSimpleName());}
            }});
            available=true;
        }catch(Throwable e){available=false;XposedBridge.log("XVideoSave: playback quality unavailable: "+e.getClass().getSimpleName());}
    }
    static void initialize(Context context){preferences=context.getSharedPreferences("xvideosave_playback",Context.MODE_PRIVATE);int saved=preferences.getInt("mode",0);mode=saved>=0&&saved<LABELS.length?saved:0;}
    private static void intField(Object b,String field,int value)throws Exception{Field f=builderClass.getDeclaredField(field);f.setAccessible(true);f.setInt(b,value);}
    private static void boolField(Object b,String field,boolean value)throws Exception{Field f=builderClass.getDeclaredField(field);f.setAccessible(true);f.setBoolean(b,value);}
    private static Object modify(Object params,int selected)throws Exception {
        Object b=parameterClass.getMethod("a").invoke(params);
        int dimension=selected==1?Integer.MAX_VALUE:selected==2?1280:854;
        intField(b,"a",dimension);intField(b,"b",dimension);
        intField(b,"c",Integer.MAX_VALUE);intField(b,"d",Integer.MAX_VALUE);
        intField(b,"e",0);intField(b,"f",0);
        intField(b,"i",Integer.MAX_VALUE);intField(b,"j",Integer.MAX_VALUE);boolField(b,"k",false);
        boolField(b,"F",false);boolField(b,"G",true);
        return b.getClass().getMethod("b").invoke(b);
    }
    static void playerCreated(Object player){if(available&&mode!=0)applyLater(player);}
    private static void applyLater(Object player){
        try {Looper looper=(Looper)player.getClass().getMethod("b1").invoke(player);if(looper==null)return;
            new Handler(looper).post(()->apply(player));
        }catch(Throwable e){XposedBridge.log("XVideoSave: playback scheduling unavailable: "+e.getClass().getSimpleName());}
    }
    private static void apply(Object player){
        try {
            Object original=baseline.get(player);
            if(original==null){original=getter.invoke(player);if(original==null)return;baseline.put(player,original);}
            Object result=mode==0?original:modify(original,mode);
            internal.set(true);try{setter.invoke(player,result);}finally{internal.remove();}
            XposedBridge.log("XVideoSave: playback quality applied mode="+mode);
        }catch(Throwable e){XposedBridge.log("XVideoSave: playback quality apply failed: "+e.getClass().getSimpleName());}
    }
    private static String info(Activity a, QualitySelector.Available variants) {
        String current=PlayerTracker.currentResolution(a);
        StringBuilder text=new StringBuilder("当前播放：").append(current==null?"暂未识别":current);
        if(variants==null)return text.append("\n源画质：正在读取…").toString();
        if(variants.resolutions.isEmpty())return text.append("\n源画质：列表未提供分辨率，无法判断最高画质").toString();
        text.append("\n源可用分辨率：").append(android.text.TextUtils.join("、",variants.resolutions));
        text.append("\n列表中最高：").append(variants.resolutions.get(0));
        if(variants.incomplete)text.append("（部分档位未标明分辨率，不能确定是否为最高）");
        else if(current!=null)text.append(current.equals(variants.resolutions.get(0))?"\n当前分辨率已达到列表最高":"\n当前分辨率与列表最高不一致");
        text.append("\n原画优先是播放策略，实际画质可能随网络和设备变化；分辨率相同不代表码率相同。");
        return text.toString();
    }
    static void showDialog(Activity a){
        if(!available){Toast.makeText(a,"当前 X 版本暂不支持播放画质设置",Toast.LENGTH_LONG).show();return;}
        PlayerTracker.Current current=PlayerTracker.current(a);
        String source=Module.currentSource(a,current);
        android.widget.LinearLayout header=new android.widget.LinearLayout(a);
        header.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad=(int)(20*a.getResources().getDisplayMetrics().density);
        header.setPadding(pad,pad,pad,pad/2);
        android.widget.TextView title=new android.widget.TextView(a);title.setText("播放画质");title.setTextSize(20);header.addView(title);
        android.widget.TextView detail=new android.widget.TextView(a);detail.setText(info(a,null));detail.setTextSize(14);header.addView(detail);
        AlertDialog dialog=new AlertDialog.Builder(a).setCustomTitle(header)
            .setSingleChoiceItems(LABELS,mode,(d,index)->{
                mode=index;if(preferences!=null)preferences.edit().putInt("mode",index).apply();
                for(Object p:PlayerTracker.snapshot())applyLater(p);
                d.dismiss();Toast.makeText(a,"播放画质："+LABELS[index]+"，播放片刻后可重新打开查看实际分辨率",Toast.LENGTH_LONG).show();
            }).setNegativeButton("关闭",null).create();
        dialog.show();
        if(source==null || !android.net.Uri.parse(source).getPath().endsWith(".m3u8")) {
            detail.setText("当前播放："+Objects.toString(PlayerTracker.currentResolution(a),"暂未识别")+"\n未取得源画质列表，无法判断最高画质。请播放几秒后重试。");
            return;
        }
        Thread worker=new Thread(()->{
            QualitySelector.Available result=null;
            try {result=QualitySelector.available(Downloader.text(source));}catch(Exception ignored){}
            final QualitySelector.Available loaded=result;
            a.runOnUiThread(()->{
                if(!dialog.isShowing() || a.isFinishing())return;
                PlayerTracker.Current now=PlayerTracker.current(a);
                if(now==null || !now.id.equals(current.id)){detail.setText("视频已切换，请重新打开画质设置。");return;}
                if(loaded==null)detail.setText("当前播放："+Objects.toString(PlayerTracker.currentResolution(a),"暂未识别")+"\n源画质列表读取失败，无法判断最高画质。");
                else detail.setText(info(a,loaded));
            });
        },"xvideosave-quality-info");worker.setDaemon(true);worker.start();
    }
}
