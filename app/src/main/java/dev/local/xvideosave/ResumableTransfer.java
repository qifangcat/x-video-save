package dev.local.xvideosave;

import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.Properties;
import java.util.regex.*;

/** A cache entry is reusable only for the same URL and a completely received response. */
final class ResumableTransfer {
    interface Connector { HttpURLConnection open(String url) throws IOException; }
    interface Progress { void update(long received, long total); }
    private static final Pattern RANGE=Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");

    static String hash(String text) throws IOException {
        try { byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8")); StringBuilder s=new StringBuilder(); for(byte b:bytes)s.append(String.format(java.util.Locale.US,"%02x",b & 255)); return s.toString(); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    }
    static void get(String url, File file, long limit, Connector connector, Progress progress) throws IOException {
        IOException failure=null;
        for(int attempt=0;attempt<3;attempt++) {
            try { once(url,file,limit,connector,progress); return; }
            catch(IOException e) { failure=e; if(attempt<2)try{Thread.sleep(1000L*(attempt+1));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("下载已中断",interrupted);} }
        }
        throw failure;
    }
    private static void once(String url, File file, long limit, Connector connector, Progress progress) throws IOException {
        File meta=new File(file.getPath()+".meta"), done=new File(file.getPath()+".done");
        Properties p=new Properties();
        try(FileInputStream in=new FileInputStream(meta)){p.load(in);}catch(IOException ignored){}
        String key=hash(url); long expected=parse(p.getProperty("length"));
        if(!key.equals(p.getProperty("url")) || !file.isFile() || file.length()>limit) { file.delete(); meta.delete(); done.delete(); p.clear(); expected=-1; }
        if(done.isFile() && file.isFile() && file.length()>0 && (expected<0 || expected==file.length())) { progress.update(file.length(),file.length()); return; }
        done.delete();
        long offset=file.isFile()?file.length():0;
        String validator=p.getProperty("validator");
        if(offset>0 && (validator==null || expected<=offset)) { file.delete(); offset=0; }
        HttpURLConnection c=connector.open(url);
        try {
            c.setRequestProperty("Accept-Encoding","identity");
            if(offset>0){c.setRequestProperty("Range","bytes="+offset+"-");c.setRequestProperty("If-Range",validator);}
            int code=c.getResponseCode();
            if(code==416 && offset>0){file.delete();meta.delete();throw new IOException("续传位置已失效，重新下载");}
            if(code!=200 && code!=206)throw new IOException("媒体服务器返回 "+code);
            long total;
            if(code==206) {
                Matcher m=RANGE.matcher(String.valueOf(c.getHeaderField("Content-Range")));
                if(offset==0 || !m.matches() || parse(m.group(1))!=offset || parse(m.group(3))!=expected || parse(m.group(2))!=expected-1) { file.delete();meta.delete();throw new IOException("服务器返回的续传范围无效"); }
                total=expected;
            } else { offset=0; total=c.getContentLengthLong(); try(FileOutputStream truncate=new FileOutputStream(file)){/* discard the old representation before recording its replacement */} }
            if(total>limit || total==0) {file.delete();meta.delete();throw new IOException("视频数据大小异常");}
            String etag=c.getHeaderField("ETag"), modified=c.getHeaderField("Last-Modified");
            String next=etag!=null && !etag.startsWith("W/") ? etag : modified;
            if(code==206){if(next!=null && !next.equals(validator)){file.delete();meta.delete();throw new IOException("媒体内容已变化");} next=validator;}
            p.clear();p.setProperty("url",key);p.setProperty("length",Long.toString(total));if(next!=null)p.setProperty("validator",next);
            try(FileOutputStream out=new FileOutputStream(meta)){p.store(out,null);out.getFD().sync();}
            long received=offset;
            try(InputStream in=c.getInputStream();FileOutputStream raw=new FileOutputStream(file,offset>0);OutputStream out=new BufferedOutputStream(raw)) {
                byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1){received+=n;if(received>limit || (total>=0 && received>total))throw new IOException("视频数据大小异常");out.write(buf,0,n);progress.update(received,total);}
            }
            if(received==0 || (total>=0 && received!=total))throw new IOException("下载不完整");
            try(FileOutputStream out=new FileOutputStream(done)){out.write(1);}
        } finally {c.disconnect();}
    }
    private static long parse(String value){try{return Long.parseLong(value);}catch(Exception ignored){return -1;}}
}
