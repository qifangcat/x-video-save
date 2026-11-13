package dev.local.xvideosave;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

public class ResumableTransferTest {
    static final byte[] DATA=new byte[200000];
    static class Server implements ResumableTransfer.Connector {
        int calls, failUntil=1; boolean range=true, validator=true, badRange=false; final List<String> ranges=new ArrayList<>();
        public HttpURLConnection open(String url) throws IOException {return new Fake(new URL(url),this);}
    }
    static class Fake extends HttpURLConnection {
        final Server server; int number; String range;
        Fake(URL url,Server server){super(url);this.server=server;}
        public int getResponseCode(){number=++server.calls;range=getRequestProperty("Range");server.ranges.add(range);return range!=null&&server.range?206:200;}
        public String getHeaderField(String name){
            if(name.equalsIgnoreCase("ETag"))return server.validator?"\"v1\"":null;
            if(name.equalsIgnoreCase("Content-Range") && range!=null && server.range){int start=Integer.parseInt(range.substring(6,range.length()-1));return "bytes "+(server.badRange?start+1:start)+"-"+(DATA.length-1)+"/"+DATA.length;}
            return null;
        }
        public long getContentLengthLong(){return DATA.length;}
        public InputStream getInputStream(){int start=range!=null&&server.range?Integer.parseInt(range.substring(6,range.length()-1)):0;int end=number<=server.failUntil?80000:DATA.length;return new ByteArrayInputStream(DATA,start,end-start);}
        public void disconnect(){} public boolean usingProxy(){return false;} public void connect(){}
    }
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static void run(File root, String name, Server server, boolean expectRange) throws Exception {
        File file=new File(root,name);ResumableTransfer.get("https://example.test/"+name,file,DATA.length+1,server,(n,t)->{});
        check(Arrays.equals(DATA,Files.readAllBytes(file.toPath())),name+" data");
        int expectedCalls=server.badRange?3:2;check(server.calls==expectedCalls,name+" retries");check((server.ranges.get(1)!=null)==expectRange,name+" range");if(server.badRange)check(server.ranges.get(2)==null,"invalid range restarts");
        ResumableTransfer.get("https://example.test/"+name,file,DATA.length+1,server,(n,t)->{});check(server.calls==expectedCalls,name+" completed cache");
    }
    public static void main(String[] args) throws Exception {
        new Random(42).nextBytes(DATA);File root=Files.createTempDirectory("resume-test").toFile();
        run(root,"range",new Server(),true);
        Server noRange=new Server();noRange.range=false;run(root,"ignored",noRange,true);
        Server noValidator=new Server();noValidator.validator=false;run(root,"no-validator",noValidator,false);
        Server invalid=new Server();invalid.badRange=true;run(root,"invalid",invalid,true);
        Server interrupted=new Server();interrupted.failUntil=3;File partial=new File(root,"interrupted");
        try{ResumableTransfer.get("https://example.test/interrupted",partial,DATA.length+1,interrupted,(n,t)->{});throw new AssertionError("interruption should fail");}catch(IOException expected){}
        check(partial.length()==80000,"partial data retained");
        ResumableTransfer.get("https://example.test/interrupted",partial,DATA.length+1,interrupted,(n,t)->{});
        check("bytes=80000-".equals(interrupted.ranges.get(3)),"manual retry resumes");check(Arrays.equals(DATA,Files.readAllBytes(partial.toPath())),"manual retry data");
        File file=new File(root,"change");Server first=new Server();ResumableTransfer.get("https://example.test/first",file,DATA.length+1,first,(n,t)->{});Server second=new Server();second.calls=1;ResumableTransfer.get("https://example.test/second",file,DATA.length+1,second,(n,t)->{});check(second.calls==2 && second.ranges.get(0)==null,"changed URL starts fresh");
        System.out.println("ResumableTransfer tests passed");
    }
}
