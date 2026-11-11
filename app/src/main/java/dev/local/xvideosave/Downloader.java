package dev.local.xvideosave;

import android.content.*;
import android.database.Cursor;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.widget.Toast;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.regex.*;

final class Downloader {
    private static final Pattern ATTR=Pattern.compile("(?:^|,)([A-Z0-9-]+)=(?:\\\"([^\\\"]*)\\\"|([^,]*))");
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.ExecutorService WORK=java.util.concurrent.Executors.newFixedThreadPool(2);
    private static final Object LOCK=new Object();
    private static final Map<String,Task> TASKS=new LinkedHashMap<>();
    private static final ThreadLocal<Task> RUNNING=new ThreadLocal<>();
    private static final int MAX_TASKS=20;
    private static final class Task {
        final String id;
        String stage="排队中";
        Task(String id){this.id=id;}
        String shortId(){return id.substring(Math.max(0,id.length()-6));}
    }
    private static volatile String currentStatus="保存视频";
    static String status(){return currentStatus;}
    private static void refreshLocked(){
        int count=TASKS.size();
        currentStatus=count==0?"保存视频":count==1?TASKS.values().iterator().next().stage:"保存 · "+count;
        Module.setButtonStatus(currentStatus);
    }
    private static void status(String value){
        Task task=RUNNING.get();
        if(task==null)return;
        synchronized(LOCK){if(TASKS.get(task.id)==task){task.stage=value;refreshLocked();}}
    }
    static void start(Context ctx,String url,String id,QualitySelector.Quality quality){
      final Task task;
      synchronized(LOCK){
        if(TASKS.containsKey(id)){message(ctx,"该视频已在下载或排队中");return;}
        if(TASKS.size()>=MAX_TASKS){message(ctx,"下载任务已满，请稍后再试");return;}
        task=new Task(id); TASKS.put(id,task); refreshLocked();
      }
      message(ctx,"已添加视频 "+task.shortId()+" 的下载");
      WORK.execute(()->{
        RUNNING.set(task); status("准备中");
        File dir=new File(ctx.getCacheDir(),"xvideosave-"+UUID.randomUUID()); dir.mkdirs(); Uri output=null;
        try {
            boolean direct=safe(url).getPath().endsWith(".mp4"); File v; File a=null;
            if(direct){if(quality!=QualitySelector.Quality.ORIGINAL)throw new IOException("该视频只提供一个版本，请轻点按钮下载");status("下载中");v=new File(dir,"video.mp4");try(OutputStream o=new BufferedOutputStream(new FileOutputStream(v))){copyDirect(url,o);}verifyDirect(v);}
            else {Master master=master(url,quality);if(master.resolution!=null)message(ctx,(master.aboveLimit?"没有对应档位，使用最低可用画质：":"下载画质：")+master.resolution);v=track(master.video,new File(dir,"video.bin"),"画面");a=master.audio==null?null:track(master.audio,new File(dir,"audio.bin"),"声音");}
            ContentValues cv=new ContentValues(); cv.put(MediaStore.Video.Media.DISPLAY_NAME,"X_"+id+"_"+System.currentTimeMillis()+".mp4"); cv.put(MediaStore.Video.Media.MIME_TYPE,"video/mp4"); cv.put(MediaStore.Video.Media.RELATIVE_PATH,Environment.DIRECTORY_MOVIES+"/X视频保存"); cv.put(MediaStore.Video.Media.IS_PENDING,1);
            ContentResolver cr=ctx.getContentResolver(); output=cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,cv); if(output==null)throw new IOException("无法创建媒体文件");
            if(direct){try(InputStream in=new FileInputStream(v);OutputStream out=cr.openOutputStream(output,"w")){if(out==null)throw new IOException("无法打开媒体文件");byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}}
            else try(ParcelFileDescriptor fd=cr.openFileDescriptor(output,"w")){if(fd==null)throw new IOException("无法打开媒体文件");status("合并中");if(android.os.Build.VERSION.SDK_INT<=33)muxCompatible(v,a,fd);else mux(v,a,fd);}
            cv.clear();cv.put(MediaStore.Video.Media.IS_PENDING,0);if(cr.update(output,cv,null,null)!=1)throw new IOException("无法发布文件");output=null;
            message(ctx,"视频 "+task.shortId()+" 已保存到相册");
        }catch(Throwable e){if(output!=null)ctx.getContentResolver().delete(output,null,null);String reason=e.getMessage();if(reason==null)reason=e.getClass().getSimpleName(); final String msg=reason.length()>100?reason.substring(0,100):reason;message(ctx,"视频 "+task.shortId()+" 保存失败："+msg);logFailure(e);}
        finally {try{File[] fs=dir.listFiles();if(fs!=null)for(File f:fs)f.delete();dir.delete();}finally{RUNNING.remove();synchronized(LOCK){TASKS.remove(task.id);refreshLocked();}}}
    }); }
    private static void message(Context c,String s){MAIN.post(()->Toast.makeText(c,s,Toast.LENGTH_LONG).show());}
    private static void logFailure(Throwable error){
        for(int depth=0;error!=null&&depth<5;depth++,error=error.getCause()){
            StringBuilder b=new StringBuilder("failure cause ").append(depth).append(": ").append(error.getClass().getName());
            StackTraceElement[] trace=error.getStackTrace();
            for(int i=0;i<Math.min(4,trace.length);i++)b.append(" | ").append(trace[i].getClassName()).append('.').append(trace[i].getMethodName()).append(':').append(trace[i].getLineNumber());
            android.util.Log.w("XVideoSave",b.toString());
        }
    }
    private static Map<String,String> attrs(String s){Map<String,String> out=new HashMap<>();Matcher m=ATTR.matcher(s);while(m.find())out.put(m.group(1),m.group(2)!=null?m.group(2):m.group(3));return out;}
    private static URL safe(String s)throws IOException {URL u=new URL(s);if(!"https".equalsIgnoreCase(u.getProtocol()) || !"video.twimg.com".equalsIgnoreCase(u.getHost()) || (u.getPort()!=-1 && u.getPort()!=443) || u.getUserInfo()!=null)throw new IOException("不支持的媒体地址");return u;}
    private static String resolve(String base,String relative)throws IOException {return safe(new URL(safe(base),relative).toString()).toString();}
    private static HttpURLConnection open(String u)throws IOException {HttpURLConnection c=(HttpURLConnection)safe(u).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setInstanceFollowRedirects(false);c.setRequestProperty("User-Agent","Mozilla/5.0");int status=c.getResponseCode();if(status!=200){c.disconnect();throw new IOException("媒体服务器返回 "+status);}return c;}
    private static String text(String u)throws IOException {HttpURLConnection c=open(u);try(InputStream in=c.getInputStream();ByteArrayOutputStream b=new ByteArrayOutputStream()){byte[] x=new byte[8192];int n,total=0;while((n=in.read(x))!=-1){total+=n;if(total>2_000_000)throw new IOException("播放列表过大");b.write(x,0,n);}return b.toString("UTF-8");}finally{c.disconnect();}}
    private static class Master{String video,audio,resolution;boolean aboveLimit;}
    private static Master master(String url)throws IOException {
        return master(url,QualitySelector.Quality.ORIGINAL);
    }
    private static Master master(String url,QualitySelector.Quality quality)throws IOException {
        QualitySelector.Selection s=QualitySelector.select(text(url),quality);Master m=new Master();
        m.video=resolve(url,s.video);m.audio=s.audio==null?null:resolve(url,s.audio);
        m.resolution=s.width>0?s.width+"×"+s.height:null;m.aboveLimit=s.aboveLimit;return m;
    }
    private static File track(String playlist,File file)throws IOException {return track(playlist,file,"视频");}
    private static File track(String playlist,File file,String label)throws IOException {
        String text=text(playlist);String[] lines=text.split("\\r?\\n"); if(!text.contains("#EXT-X-ENDLIST"))throw new IOException("不支持直播或未完成的视频");
        boolean init=false;int segments=0;long total=0;int count=0;for(String raw:lines){String line=raw.trim();if(!line.isEmpty()&&!line.startsWith("#"))count++;}
        try(OutputStream out=new BufferedOutputStream(new FileOutputStream(file))){
            for(String raw:lines){String line=raw.trim();if(line.startsWith("#EXT-X-KEY:") && !line.contains("METHOD=NONE"))throw new IOException("不支持加密视频");
                if(line.startsWith("#EXT-X-BYTERANGE") || line.startsWith("#EXT-X-DISCONTINUITY"))throw new IOException("暂不支持该分片格式");
                if(line.startsWith("#EXT-X-MAP:")){if(init)throw new IOException("暂不支持多个初始化分片");Map<String,String>a=attrs(line.substring(11));String u=a.get("URI");if(u==null||a.containsKey("BYTERANGE"))throw new IOException("不支持的初始化分片");total+=copy(resolve(playlist,u),out);init=true;}
                else if(!line.isEmpty()&&!line.startsWith("#")){if(++segments>10000)throw new IOException("分片过多");status(label+" "+segments+"/"+count);total+=copy(resolve(playlist,line),out);if(total>1_073_741_824L)throw new IOException("视频过大");}
            }
        } if(!init || segments==0)throw new IOException("视频分片不完整");return file;
    }
    private static long copy(String url,OutputStream out)throws IOException {HttpURLConnection c=open(url);long total=0;try(InputStream in=c.getInputStream()){byte[] buf=new byte[64*1024];int n;while((n=in.read(buf))!=-1){total+=n;if(total>256L*1024*1024)throw new IOException("单个分片过大");out.write(buf,0,n);}return total;}finally{c.disconnect();}}
    private static long copyDirect(String url,OutputStream out)throws IOException {HttpURLConnection c=open(url);long total=0,last=0;long length=c.getContentLengthLong();try(InputStream in=c.getInputStream()){byte[] buf=new byte[64*1024];int n;while((n=in.read(buf))!=-1){total+=n;if(total>1_073_741_824L)throw new IOException("视频过大");out.write(buf,0,n);long now=System.currentTimeMillis();if(now-last>500){status(length>0?"下载 "+(total*100/length)+"%":"下载 "+(total/1048576)+"M");last=now;}}return total;}finally{c.disconnect();}}
    private static void verifyDirect(File file)throws IOException {MediaExtractor ex=new MediaExtractor();try{ex.setDataSource(file.getAbsolutePath());boolean ok=false;for(int i=0;i<ex.getTrackCount();i++){MediaFormat f=ex.getTrackFormat(i);String mime=f.getString(MediaFormat.KEY_MIME);if(mime!=null&&mime.startsWith("video/")){ex.selectTrack(i);ok=ex.readSampleData(ByteBuffer.allocateDirect(16*1024*1024),0)>0;break;}}if(!ok)throw new IOException("视频文件没有可播放画面");}finally{ex.release();}}
    private static void muxCompatible(File video,File audio,ParcelFileDescriptor fd)throws IOException {
        ParsedMovie vm=null,am=null;
        try {
            vm=new ParsedMovie(video);
            if(audio!=null)am=new ParsedMovie(audio);
            org.mp4parser.muxer.Movie result=new org.mp4parser.muxer.Movie();
            result.setMatrix(vm.movie.getMatrix());
            int videoCount=0,audioCount=0;
            for(org.mp4parser.muxer.Track t:vm.movie.getTracks()){
                if("vide".equals(t.getHandler())){result.addTrack(t);videoCount++;}
                else if(audio==null&&"soun".equals(t.getHandler())){result.addTrack(t);audioCount++;}
            }
            if(am!=null)for(org.mp4parser.muxer.Track t:am.movie.getTracks())if("soun".equals(t.getHandler())){result.addTrack(t);audioCount++;}
            if(videoCount!=1||audioCount>1||(audio!=null&&audioCount!=1))throw new IOException("音视频轨道不完整");
            for(org.mp4parser.muxer.Track t:result.getTracks())if(t.getSamples().isEmpty())throw new IOException("音视频分片无内容");
            try(ParcelFileDescriptor copy=ParcelFileDescriptor.dup(fd.getFileDescriptor());FileOutputStream output=new FileOutputStream(copy.getFileDescriptor())){
                new org.mp4parser.muxer.builder.DefaultMp4Builder().build(result).writeContainer(output.getChannel());output.flush();
            }
        }catch(IOException e){throw e;}catch(Exception e){throw new IOException("无法合并视频分片",e);}
        finally{if(vm!=null)vm.close();if(am!=null)am.close();}
    }
    private static final class ParsedMovie implements java.io.Closeable {
        final org.mp4parser.muxer.Movie movie=new org.mp4parser.muxer.Movie();
        private org.mp4parser.IsoFile iso;
        private org.mp4parser.muxer.FileRandomAccessSourceImpl source;
        ParsedMovie(File file)throws IOException {
            try {
                java.util.Properties mapping=Mp4BoxMappings.load();
                try(FileInputStream input=new FileInputStream(file)){
                    iso=new org.mp4parser.IsoFile(input.getChannel(),new org.mp4parser.PropertyBoxParserImpl(mapping));
                }
                source=new org.mp4parser.muxer.FileRandomAccessSourceImpl(new RandomAccessFile(file,"r"));
                if(iso.getMovieBox()==null)throw new IOException("缺少视频头");
                for(org.mp4parser.boxes.iso14496.part12.TrackBox track:iso.getMovieBox().getBoxes(org.mp4parser.boxes.iso14496.part12.TrackBox.class))
                    movie.addTrack(new org.mp4parser.muxer.Mp4TrackImpl(track.getTrackHeaderBox().getTrackId(),iso,source,file.getName()));
                movie.setMatrix(iso.getMovieBox().getMovieHeaderBox().getMatrix());
            }catch(Throwable e){close();if(e instanceof IOException)throw (IOException)e;throw new IOException("无法读取视频分片",e);}
        }
        @Override public void close(){for(org.mp4parser.muxer.Track t:movie.getTracks())try{t.close();}catch(IOException ignored){}if(source!=null)try{source.close();}catch(IOException ignored){}if(iso!=null)try{iso.close();}catch(IOException ignored){}}
    }
    private static class Track {MediaExtractor ex;int index,out;Track(MediaExtractor e,int i,int o){ex=e;index=i;out=o;}}
    private static void mux(File video,File audio,ParcelFileDescriptor fd)throws IOException {
        MediaExtractor ve=new MediaExtractor(),ae=null;MediaMuxer mux=null;boolean started=false;ArrayList<Track> tracks=new ArrayList<>();
        try {ve.setDataSource(video.getAbsolutePath());if(audio!=null){ae=new MediaExtractor();ae.setDataSource(audio.getAbsolutePath());}
            mux=new MediaMuxer(fd.getFileDescriptor(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            collect(ve,mux,tracks,audio!=null);if(ae!=null)collect(ae,mux,tracks,false);
            boolean hasVideo=false,hasAudio=false;for(Track t:tracks){String mime=t.ex.getTrackFormat(t.index).getString(MediaFormat.KEY_MIME);if(mime.startsWith("video/"))hasVideo=true;if(mime.startsWith("audio/"))hasAudio=true;}
            if(!hasVideo||(audio!=null&&!hasAudio))throw new IOException("音视频轨道不完整");mux.start();started=true;
            ByteBuffer buffer=ByteBuffer.allocateDirect(16*1024*1024);MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            for(Track t:tracks){
                // A fresh extractor starts at the first sample. Some Android 13 extractors crash
                // when seeking a concatenation of fragmented MP4 segments, even to time zero.
                MediaExtractor reader=new MediaExtractor();
                try{reader.setDataSource((t.ex==ve?video:audio).getAbsolutePath());reader.selectTrack(t.index);int samples=0;
                    while(true){buffer.clear();int size=reader.readSampleData(buffer,0);if(size<0)break;long pts=reader.getSampleTime();if(pts<0)throw new IOException("音视频时间戳异常");int flags=(reader.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC)!=0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;info.set(0,size,pts,flags);mux.writeSampleData(t.out,buffer,info);samples++;reader.advance();}
                    if(samples==0)throw new IOException("视频分片无内容");
                }finally{reader.release();}
            }
            mux.stop(); started=false;
        }catch(IllegalArgumentException|IllegalStateException e){throw new IOException("无法合并视频分片",e);}finally{if(mux!=null){if(started)try{mux.stop();}catch(Exception ignored){}mux.release();}ve.release();if(ae!=null)ae.release();}
    }
    private static void collect(MediaExtractor ex,MediaMuxer mux,List<Track> list,boolean videoOnly){for(int i=0;i<ex.getTrackCount();i++){MediaFormat f=ex.getTrackFormat(i);String mime=f.getString(MediaFormat.KEY_MIME);if(mime!=null && (mime.startsWith("video/") || (!videoOnly && mime.startsWith("audio/"))))list.add(new Track(ex,i,mux.addTrack(f)));}}
}
