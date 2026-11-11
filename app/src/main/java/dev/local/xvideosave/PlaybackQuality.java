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
    static void showDialog(Activity a){
        if(!available){Toast.makeText(a,"当前 X 版本暂不支持播放画质设置",Toast.LENGTH_LONG).show();return;}
        String resolution=PlayerTracker.currentResolution(a);
        new AlertDialog.Builder(a).setTitle(resolution==null?"播放画质":"播放画质（当前 "+resolution+"）")
            .setSingleChoiceItems(LABELS,mode,(dialog,index)->{
                mode=index;if(preferences!=null)preferences.edit().putInt("mode",index).apply();
                for(Object p:PlayerTracker.snapshot())applyLater(p);
                dialog.dismiss();Toast.makeText(a,"播放画质："+LABELS[index]+"，网络较慢可能缓冲",Toast.LENGTH_LONG).show();
            }).setNegativeButton("取消",null).show();
    }
}
