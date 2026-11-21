package dev.local.xvideosave;
import android.net.Uri;
import android.app.Activity;
import android.graphics.Rect;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;
import java.util.regex.*;
import de.robv.android.xposed.*;
final class PlayerTracker {
 private static final ArrayList<WeakReference<Object>> players=new ArrayList<>();
 private static volatile WeakReference<Object> lastPlaying=new WeakReference<>(null);
 private static Method mediaItem,formatGetter,boundPlayer;
 private static Field videoView,localConfiguration,mediaUri;
 private static Class<?> playerViewClass;
 static final class Current {final String id;final Uri uri;Current(String i,Uri u){id=i;uri=u;}}
 static void install(ClassLoader loader){try{
   Class<?> c=Class.forName("androidx.media3.exoplayer.j0",false,loader);
   resolvePlayer(c);
   try {
    playerViewClass=Class.forName("androidx.media3.ui.PlayerView",false,loader);
    boundPlayer=playerViewClass.getMethod("getPlayer");
   }catch(ReflectiveOperationException ignored){}
   for(Constructor<?> ctor:c.getDeclaredConstructors())XposedBridge.hookMethod(ctor,new XC_MethodHook(){protected void afterHookedMethod(MethodHookParam p){synchronized(players){for(WeakReference<Object> r:players)if(r.get()==p.thisObject)return;players.add(new WeakReference<>(p.thisObject));}PlaybackQuality.playerCreated(p.thisObject);}});
   Class<?> base=c.getSuperclass();
   XposedBridge.hookMethod(base.getDeclaredMethod("isPlaying"),new XC_MethodHook(){protected void afterHookedMethod(MethodHookParam p){if(Boolean.TRUE.equals(p.getResult())&&c.isInstance(p.thisObject)&&visible(p.thisObject))lastPlaying=new WeakReference<>(p.thisObject);}});
   XposedBridge.log("XVideoSave: player tracking installed");
 }catch(Throwable e){XposedBridge.log("XVideoSave: tracking unavailable "+e.getClass().getSimpleName());}}
 private static void resolvePlayer(Class<?> player) throws ReflectiveOperationException{
  videoView=uniquePlayerField(player,TextureView.class);
  for(Method getter:player.getMethods()){
   if(!isMediaValueGetter(getter))continue;
   Class<?> value=getter.getReturnType();
   Field config,uri;
   try {
    if(value.getDeclaredField("a").getType()!=String.class)continue;
    config=value.getDeclaredField("b");
    uri=config.getType().getDeclaredField("a");
   }catch(NoSuchFieldException otherValue){
    continue;
   }
   if(uri.getType()!=Uri.class)continue;
   if(mediaItem!=null)throw new NoSuchMethodException("ambiguous media item getter");
   config.setAccessible(true);
   uri.setAccessible(true);
   mediaItem=getter;
   localConfiguration=config;
   mediaUri=uri;
  }
  if(mediaItem==null)throw new NoSuchMethodException("media item getter");
  formatGetter=findFormatGetter(player);
 }
 private static Method findFormatGetter(Class<?> player){
  Method result=null;
  for(Method getter:player.getMethods()){
   if(!isMediaValueGetter(getter))continue;
   Class<?> value=getter.getReturnType();
   try {
    if(value.getDeclaredField("i").getType()!=int.class || value.getDeclaredField("j").getType()!=int.class
         || value.getDeclaredField("k").getType()!=int.class || value.getDeclaredField("w").getType()!=int.class
         || value.getDeclaredField("x").getType()!=int.class
         || value.getDeclaredField("p").getType()!=String.class)continue;
   }catch(NoSuchFieldException otherValue){
    continue;
   }
   if(result!=null)return null;
   result=getter;
  }
  return result;
 }
 static boolean isMediaValueGetter(Method method){
  return !Modifier.isStatic(method.getModifiers()) && !method.isBridge() && method.getParameterCount()==0
    && method.getReturnType().getName().startsWith("androidx.media3.common.");
 }
 static Field uniquePlayerField(Class<?> player,Class<?> type) throws NoSuchFieldException{
  Field result=null;
  for(Field field:player.getDeclaredFields()){
   if(Modifier.isStatic(field.getModifiers()) || field.getType()!=type)continue;
   if(result!=null)throw new NoSuchFieldException("ambiguous "+type.getSimpleName());
   result=field;
  }
  if(result==null)throw new NoSuchFieldException(type.getSimpleName());
  result.setAccessible(true);
  return result;
 }
 private static boolean visible(Object p){return visible(p,null);}
 private static boolean visible(Object p,Activity activity){try{View v=(View)videoView.get(p);if(v==null)return false;Rect r=new Rect();
  if(!v.isShown() || !v.getGlobalVisibleRect(r) || r.width()<=30 || r.height()<=30)return false;
  if(activity==null)return true;
  View root=activity.getWindow().getDecorView();
  if(v.getRootView()!=root || root.getWidth()<=0 || root.getHeight()<=0)return false;
  // Judge the PlayerView container because landscape content is letterboxed.
  ViewParent parent=v.getParent();
  while(parent instanceof View){
   View host=(View)parent;
   if("androidx.media3.ui.PlayerView".equals(host.getClass().getName())){
    return fullscreen(host,root);
   }
   parent=host.getParent();
  }
  return false;
 }catch(Throwable e){return false;}}
 private static boolean fullscreen(View host,View root){
  Rect bounds=new Rect();
  return host.isShown() && host.getGlobalVisibleRect(bounds) && root.getWidth()>0 && root.getHeight()>0
    && bounds.width()>=root.getWidth()*0.85f && bounds.height()>=root.getHeight()*0.72f;
 }
 private static void collectBoundPlayers(View view,View root,Set<Object> result){
  if(view.getVisibility()!=View.VISIBLE)return;
  if(playerViewClass.isInstance(view) && fullscreen(view,root)){
   try {
    Object player=boundPlayer.invoke(view);
    if(player!=null && mediaItem.getDeclaringClass().isInstance(player))result.add(player);
   }catch(ReflectiveOperationException ignored){}
  }
  if(view instanceof ViewGroup){
   ViewGroup group=(ViewGroup)view;
   for(int i=0;i<group.getChildCount();i++)collectBoundPlayers(group.getChildAt(i),root,result);
  }
 }
 private static Uri itemUri(Object player){
  try {
   Object item=mediaItem.invoke(player);
   Object config=item==null?null:localConfiguration.get(item);
   return config==null?null:(Uri)mediaUri.get(config);
  }catch(ReflectiveOperationException|RuntimeException ignored){return null;}
 }
 private static Current get(Uri u){
 if(u==null)return null;
 if(!"https".equals(u.getScheme())||!"video.twimg.com".equalsIgnoreCase(u.getHost()))return null;
 Matcher m=Pattern.compile("/(?:ext_tw_video|amplify_video|tweet_video)/([A-Za-z0-9_-]+)").matcher(u.getPath()==null?"":u.getPath());return m.find()?new Current(m.group(1),u):null;
 }
 private static Object currentPlayer(Activity activity,boolean reportFailure){
 Set<Object> bound=Collections.newSetFromMap(new IdentityHashMap<>());
 if(boundPlayer!=null && mediaItem!=null){
  View root=activity.getWindow().getDecorView();
  collectBoundPlayers(root,root,bound);
 }
 ArrayList<Object> playing=new ArrayList<>(),eligible=new ArrayList<>();
 Collection<Object> candidates=bound.isEmpty()?snapshot():bound;
 int visibleCount=0,uriCount=0;
 for(Object player:candidates){
  if(bound.isEmpty() && !visible(player,activity))continue;
  visibleCount++;
  Uri uri=itemUri(player);
  if(uri!=null)uriCount++;
  if(get(uri)==null)continue;
  eligible.add(player);
  try {
   if(Boolean.TRUE.equals(player.getClass().getMethod("isPlaying").invoke(player)))playing.add(player);
  }catch(ReflectiveOperationException ignored){}
 }
 if(playing.size()==1){lastPlaying=new WeakReference<>(playing.get(0));return playing.get(0);}
 if(playing.isEmpty()){
  Object last=lastPlaying.get();
  if(eligible.contains(last))return last;
  if(bound.size()==1 && eligible.size()==1)return eligible.get(0);
 }
 if(reportFailure)XposedBridge.log("XVideoSave: player selection unavailable; bound="+bound.size()
   +", visible="+visibleCount+", mediaItemUris="+uriCount+", eligible="+eligible.size()+", playing="+playing.size());
 return null;
 }
 static Current current(Activity activity){return current(activity,false);}
 static Current current(Activity activity,boolean reportFailure){Object p=currentPlayer(activity,reportFailure);return p==null?null:get(itemUri(p));}
 static String currentResolution(Activity activity){try{Object p=currentPlayer(activity,false);if(p==null||formatGetter==null)return null;Object format=formatGetter.invoke(p);if(format==null)return null;Field w=format.getClass().getDeclaredField("w"),h=format.getClass().getDeclaredField("x");w.setAccessible(true);h.setAccessible(true);int width=w.getInt(format),height=h.getInt(format);return width>0&&height>0?width+"×"+height:null;}catch(Throwable ignored){return null;}}
 static final class Bitrates {final int average,peak;Bitrates(int a,int p){average=a;peak=p;}}
 static Bitrates currentBitrates(Activity activity){try{
  Object player=currentPlayer(activity,false);if(player==null||formatGetter==null)return null;
  Object format=formatGetter.invoke(player);if(format==null)return null;
  Class<?> c=format.getClass();Field avg=c.getDeclaredField("i"),peak=c.getDeclaredField("j"),combined=c.getDeclaredField("k");
  if(avg.getType()!=int.class||peak.getType()!=int.class||combined.getType()!=int.class)return null;
  avg.setAccessible(true);peak.setAccessible(true);combined.setAccessible(true);
  int a=avg.getInt(format),p=peak.getInt(format),v=combined.getInt(format);
  if(v!=(p!=-1?p:a))return null;
  return new Bitrates(a,p);
 }catch(Throwable ignored){return null;}}
 static List<Object> snapshot(){ArrayList<Object> out=new ArrayList<>();synchronized(players){Iterator<WeakReference<Object>> it=players.iterator();while(it.hasNext()){Object p=it.next().get();if(p==null)it.remove();else out.add(p);}}return out;}
}
