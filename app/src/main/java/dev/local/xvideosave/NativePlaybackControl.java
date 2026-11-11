package dev.local.xvideosave;

import java.lang.reflect.*;
import java.util.regex.Pattern;
import de.robv.android.xposed.*;

/** Adds a sibling to X's speed control while the host composes its control row. */
final class NativePlaybackControl {
    private static final Pattern SPEED=Pattern.compile("(?:0\\.25|0\\.5|0\\.75|1(?:\\.0)?|1\\.25|1\\.5|1\\.75|2(?:\\.0)?|2\\.5|3(?:\\.0)?)\\s*[x×]",Pattern.CASE_INSENSITIVE);
    private static final ThreadLocal<Boolean> INSIDE=new ThreadLocal<>();
    private static Object click,longClick;
    private static Object progressState;
    private static Method progressRead,progressWrite;
    private static int revision;
    static void install(ClassLoader loader){
        try {
            Class<?> composer=Class.forName("androidx.compose.runtime.Composer",false,loader);
            Class<?> modifier=Class.forName("androidx.compose.ui.Modifier",false,loader);
            Class<?> callback=Class.forName("kotlin.jvm.functions.Function0",false,loader);
            Class<?> unitClass=Class.forName("kotlin.Unit",false,loader);
            Field unitField=null;for(Field f:unitClass.getDeclaredFields())if(Modifier.isStatic(f.getModifiers())&&f.getType()==unitClass){unitField=f;break;}
            if(unitField==null)throw new NoSuchFieldException("Unit singleton");
            unitField.setAccessible(true);Object unit=unitField.get(null);
            click=Proxy.newProxyInstance(loader,new Class<?>[]{callback},(proxy,method,args)->{
                if(method.getName().equals("invoke")){Module.nativeDownload();return unit;}
                if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
                if(method.getName().equals("equals"))return proxy==args[0];
                return "XVideoSaveClick";
            });
            longClick=Proxy.newProxyInstance(loader,new Class<?>[]{callback},(proxy,method,args)->{
                if(method.getName().equals("invoke")){Module.nativeOptions();return unit;}
                if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
                if(method.getName().equals("equals"))return proxy==args[0];
                return "XVideoSaveLongClick";
            });
            Method resolved=null;
            for(String name:new String[]{"com.x.ui.immersive.chrome.a","com.x.ui.immersive.chrome.b"}){
                try {Class<?> owner=Class.forName(name,false,loader);Method m=owner.getDeclaredMethod("f",int.class,long.class,composer,modifier,String.class,callback,callback);if(Modifier.isStatic(m.getModifiers())&&m.getReturnType()==void.class){resolved=m;break;}}catch(ClassNotFoundException|NoSuchMethodException ignored){}
            }
            if(resolved==null)throw new NoSuchMethodException("speed control");
            final Method speed=resolved;
            speed.setAccessible(true);
            Class<?> runtime=Class.forName("androidx.compose.runtime.o0",false,loader);
            Method begin=runtime.getMethod("k0",int.class),end=runtime.getMethod("u",boolean.class);
            Class<?> stateClass=Class.forName("androidx.compose.runtime.t1",false,loader);
            progressState=stateClass.getConstructor(int.class).newInstance(0);
            progressRead=stateClass.getMethod("getValue");progressWrite=stateClass.getMethod("k",int.class);
            XposedBridge.hookMethod(speed,new XC_MethodHook(){protected void afterHookedMethod(MethodHookParam p){
                if(Boolean.TRUE.equals(INSIDE.get()) || p.args.length!=7 || !(p.args[4] instanceof String)
                        || !SPEED.matcher(((String)p.args[4]).trim()).matches() || !runtime.isInstance(p.args[2]))return;
                Object c=p.args[2];boolean opened=false;
                try {
                    INSIDE.set(true);begin.invoke(c,0x58565331);opened=true;
                    progressRead.invoke(progressState);
                    speed.invoke(null,0,p.args[1],c,null,progressLabel(Downloader.status()),click,longClick);
                } catch(Throwable e){XposedBridge.log("XVideoSave: native control insertion failed: "+e.getClass().getSimpleName());}
                finally {if(opened)try{end.invoke(c,false);}catch(Throwable e){XposedBridge.log("XVideoSave: native group close failed: "+e.getClass().getSimpleName());}INSIDE.remove();}
            }});
            XposedBridge.log("XVideoSave: native speed-row hook installed");
        }catch(Throwable e){XposedBridge.log("XVideoSave: native speed-row hook unavailable: "+e.getClass().getSimpleName());}
    }
    static void statusChanged(){
        if(progressState==null||progressWrite==null)return;
        try{progressWrite.invoke(progressState,++revision);}
        catch(Throwable e){XposedBridge.log("XVideoSave: progress update unavailable: "+e.getClass().getSimpleName());}
    }
    static String progressLabel(String status){
        if(status==null||status.equals("保存视频"))return "↓";
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("(画面|声音) (\\d+)/(\\d+)").matcher(status);
        if(m.matches())try{
            long done=Long.parseLong(m.group(2)),total=Long.parseLong(m.group(3));
            if(total>0)return (m.group(1).equals("画面")?"画":"音")+Math.min(100,done*100/total)+"%";
        }catch(NumberFormatException ignored){}
        if(status.startsWith("下载 "))return status.substring(3);
        if(status.startsWith("保存 · "))return status.substring(5)+"项";
        if(status.equals("合并中"))return "合并";
        return "准备";
    }
}
