package dev.local.xvideosave;

import java.io.IOException;
import java.util.*;
import java.util.regex.*;

/** Selects an advertised HLS rendition; it never re-encodes the stream. */
final class QualitySelector {
    enum Quality { ORIGINAL, HD, SD }
    static final class Selection {
        final String video, audio;
        final int width, height;
        final boolean aboveLimit;
        Selection(String video, String audio, int width, int height, boolean aboveLimit) {
            this.video=video; this.audio=audio; this.width=width; this.height=height; this.aboveLimit=aboveLimit;
        }
    }
    private static final Pattern ATTR=Pattern.compile("(?:^|,)([A-Z0-9-]+)=(?:\"([^\"]*)\"|([^,]*))");
    private static Map<String,String> attrs(String s) {
        Map<String,String> out=new HashMap<>(); Matcher m=ATTR.matcher(s);
        while(m.find()) out.put(m.group(1),m.group(2)!=null?m.group(2):m.group(3));
        return out;
    }
    private static final class Variant {
        String uri, audio; int width, height; long bandwidth;
        long area(){return (long)width*height;}
        int shortSide(){return Math.min(width,height);}
    }
    static Selection select(String playlist, Quality quality) throws IOException {
        Map<String,String> audio=new HashMap<>(); List<Variant> variants=new ArrayList<>(); Map<String,String> pending=null;
        for(String raw:playlist.split("\\r?\\n")) {
            String line=raw.trim();
            if(line.startsWith("#EXT-X-MEDIA:")) {
                Map<String,String> a=attrs(line.substring(13));
                if("AUDIO".equals(a.get("TYPE")) && a.containsKey("GROUP-ID") && a.containsKey("URI"))
                    audio.put(a.get("GROUP-ID"),a.get("URI"));
            } else if(line.startsWith("#EXT-X-STREAM-INF:")) pending=attrs(line.substring(18));
            else if(!line.isEmpty()&&!line.startsWith("#")&&pending!=null) {
                Variant v=new Variant(); v.uri=line; v.audio=pending.get("AUDIO");
                try {String[] wh=pending.getOrDefault("RESOLUTION","").split("x");v.width=Integer.parseInt(wh[0]);v.height=Integer.parseInt(wh[1]);if(v.width<=0||v.height<=0){v.width=0;v.height=0;}} catch(Exception ignored){v.width=0;v.height=0;}
                try {v.bandwidth=Math.max(0,Long.parseLong(pending.getOrDefault("BANDWIDTH","0")));} catch(NumberFormatException ignored){}
                variants.add(v); pending=null;
            }
        }
        if(variants.isEmpty())throw new IOException("未找到完整视频的主播放列表");
        Comparator<Variant> rank=Comparator.comparingLong(Variant::area).thenComparingLong(v->v.bandwidth);
        Variant chosen=null; boolean above=false;
        if(quality==Quality.ORIGINAL) chosen=Collections.max(variants,rank);
        else {
            int cap=quality==Quality.HD?720:480;
            for(Variant v:variants)if(v.area()>0 && v.shortSide()<=cap && (chosen==null || rank.compare(v,chosen)>0))chosen=v;
            if(chosen==null) {
                for(Variant v:variants)if(v.area()>0 && (chosen==null || rank.compare(v,chosen)<0))chosen=v;
                if(chosen==null)throw new IOException("视频未提供分辨率，无法选择画质");
                above=true;
            }
        }
        String audioUri=chosen.audio==null?null:audio.get(chosen.audio);
        if(chosen.audio!=null&&audioUri==null)throw new IOException("找不到音轨");
        return new Selection(chosen.uri,audioUri,chosen.width,chosen.height,above);
    }
}
