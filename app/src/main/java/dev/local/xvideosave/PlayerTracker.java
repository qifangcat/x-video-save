package dev.local.xvideosave;
import android.net.Uri;
import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewParent;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;
import java.util.regex.*;
import de.robv.android.xposed.*;
final class PlayerTracker {
 private static final ArrayList<WeakReference<Object>> players=new ArrayList<>();
 private static volatile WeakReference<Object> lastPlaying=new WeakReference<>(null);
 static final class Current {final String id;final Uri uri;Current(String i,Uri u){id=i;uri=u;}}
 static void install(ClassLoader loader){try{
   Class<?> c=Class.forName("androidx.media3.exoplayer.j0",false,loader);
   for(Constructor<?> ctor:c.getDeclaredConstructors())XposedBridge.hookMethod(ctor,new XC_MethodHook(){protected void afterHookedMethod(MethodHookParam p){synchronized(players){for(WeakReference<Object> r:players)if(r.get()==p.thisObject)return;players.add(new WeakReference<>(p.thisObject));}PlaybackQuality.playerCreated(p.thisObject);}});
   Class<?> base=c.getSuperclass();
   XposedBridge.hookMethod(base.getDeclaredMethod("isPlaying"),new XC_MethodHook(){protected void afterHookedMethod(MethodHookParam p){if(Boolean.TRUE.equals(p.getResult())&&c.isInstance(p.thisObject)&&visible(p.thisObject))lastPlaying=new WeakReference<>(p.thisObject);}});
   XposedBridge.log("XVideoSave: player tracking installed");
 }catch(Throwable e){XposedBridge.log("XVideoSave: tracking unavailable "+e.getClass().getSimpleName());}}
 private static boolean visible(Object p){return visible(p,null);}
 private static boolean visible(Object p,Activity activity){try{Field f=p.getClass().getDeclaredField("a0");f.setAccessible(true);Object o=f.get(p);if(!(o instanceof View))return false;View v=(View)o;Rect r=new Rect();
  if(!v.isShown() || !v.getGlobalVisibleRect(r) || r.width()<=30 || r.height()<=30)return false;
  if(activity==null)return true;
  View root=activity.getWindow().getDecorView();
  if(v.getRootView()!=root || root.getWidth()<=0 || root.getHeight()<=0)return false;
  // Judge the PlayerView container, not the TextureView: landscape content is letterboxed.
  ViewParent parent=v.getParent();
  while(parent instanceof View){
   View host=(View)parent;
   if("androidx.media3.ui.PlayerView".equals(host.getClass().getName())){
    Rect bounds=new Rect();
    return host.isShown() && host.getGlobalVisibleRect(bounds)
      && bounds.width()>=root.getWidth()*0.85f && bounds.height()>=root.getHeight()*0.72f;
   }
   parent=host.getParent();
  }
  return false;
 }catch(Throwable e){return false;}}
 private static Current get(Object p){try{Object item=p.getClass().getMethod("P0").invoke(p);if(item==null)return null;Field local=item.getClass().getDeclaredField("b");local.setAccessible(true);Object config=local.get(item);if(config==null)return null;Field uriField=config.getClass().getDeclaredField("a");uriField.setAccessible(true);Object o=uriField.get(config);if(!(o instanceof Uri))return null;Uri u=(Uri)o;
 if(!"https".equals(u.getScheme())||!"video.twimg.com".equalsIgnoreCase(u.getHost()))return null;
 Matcher m=Pattern.compile("/(?:ext_tw_video|amplify_video|tweet_video)/([A-Za-z0-9_-]+)").matcher(u.getPath()==null?"":u.getPath());return m.find()?new Current(m.group(1),u):null;
 }catch(Throwable e){return null;}}
 private static Object currentPlayer(Activity activity){ArrayList<Object> playing=new ArrayList<>();synchronized(players){Iterator<WeakReference<Object>> it=players.iterator();while(it.hasNext()){Object p=it.next().get();if(p==null){it.remove();continue;}try{if(visible(p,activity)&&Boolean.TRUE.equals(p.getClass().getMethod("isPlaying").invoke(p)) && get(p)!=null)playing.add(p);}catch(Throwable ignored){}}}
 if(playing.size()==1){lastPlaying=new WeakReference<>(playing.get(0));return playing.get(0);}
 if(playing.size()>1)return null;
 Object last=lastPlaying.get();return last!=null&&visible(last,activity)?last:null;
 }
 static Current current(Activity activity){Object p=currentPlayer(activity);return p==null?null:get(p);}
 static String currentResolution(Activity activity){try{Object p=currentPlayer(activity);if(p==null)return null;Object format=p.getClass().getMethod("X0").invoke(p);if(format==null)return null;Field w=format.getClass().getDeclaredField("w"),h=format.getClass().getDeclaredField("x");w.setAccessible(true);h.setAccessible(true);int width=w.getInt(format),height=h.getInt(format);return width>0&&height>0?width+"×"+height:null;}catch(Throwable ignored){return null;}}
 static final class Bitrates {final int average,peak;Bitrates(int a,int p){average=a;peak=p;}}
 static Bitrates currentBitrates(Activity activity){try{
  Object player=currentPlayer(activity);if(player==null)return null;
  Object format=player.getClass().getMethod("X0").invoke(player);if(format==null)return null;
  Class<?> c=format.getClass();Field avg=c.getDeclaredField("i"),peak=c.getDeclaredField("j"),combined=c.getDeclaredField("k");
  if(avg.getType()!=int.class||peak.getType()!=int.class||combined.getType()!=int.class)return null;
  avg.setAccessible(true);peak.setAccessible(true);combined.setAccessible(true);
  int a=avg.getInt(format),p=peak.getInt(format),v=combined.getInt(format);
  if(v!=(p!=-1?p:a))return null;
  return new Bitrates(a,p);
 }catch(Throwable ignored){return null;}}
 static List<Object> snapshot(){ArrayList<Object> out=new ArrayList<>();synchronized(players){Iterator<WeakReference<Object>> it=players.iterator();while(it.hasNext()){Object p=it.next().get();if(p==null)it.remove();else out.add(p);}}return out;}
}
