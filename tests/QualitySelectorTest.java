package dev.local.xvideosave;

import java.nio.file.*;

public final class QualitySelectorTest {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        String source=Files.readString(Path.of(args[0]));
        QualitySelector.Selection original=QualitySelector.select(source,QualitySelector.Quality.ORIGINAL);
        QualitySelector.Selection hd=QualitySelector.select(source,QualitySelector.Quality.HD);
        QualitySelector.Selection sd=QualitySelector.select(source,QualitySelector.Quality.SD);
        check(original.width==720&&original.height==1280,"original resolution");
        check(hd.width==720&&hd.height==1280,"hd resolution");
        check(sd.width==480&&sd.height==852,"sd resolution");
        check(original.audio.contains("128000"),"original matching audio group");
        check(sd.audio.contains("64000"),"sd matching audio group");
        String highOnly="#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100,RESOLUTION=1920x1080\nhigh.m3u8\n";
        QualitySelector.Selection fallback=QualitySelector.select(highOnly,QualitySelector.Quality.SD);
        check(fallback.aboveLimit && fallback.width==1920,"report fallback above cap");
        String unknown="#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\na.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=200\nb.m3u8\n";
        check(QualitySelector.select(unknown,QualitySelector.Quality.ORIGINAL).video.equals("b.m3u8"),"unknown dimensions choose bandwidth");
        boolean rejected=false;try{QualitySelector.select(unknown,QualitySelector.Quality.HD);}catch(java.io.IOException expected){rejected=true;}
        check(rejected,"unknown dimensions reject capped quality");
        QualitySelector.Available listed=QualitySelector.available(source);
        check(!listed.incomplete && listed.resolutions.get(0).equals("720×1280"),"listed highest resolution");
        check(listed.maxPeak==2000000 && !listed.incompletePeak,"highest advertised peak");
        QualitySelector.Available rates=QualitySelector.available("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=3000000,AVERAGE-BANDWIDTH=2000000,RESOLUTION=1920x1080\na.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=1000000,AVERAGE-BANDWIDTH=700000,RESOLUTION=1280x720\nb.m3u8\n");
        check(rates.maxPeak==3000000 && rates.maxAverage==2000000,"peak and average kept separate");
        check(QualitySelector.available(unknown).incompletePeak==false,"bandwidth values available despite missing dimensions");
        check(QualitySelector.available("#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1920x1080\na.m3u8\n").incompletePeak,"missing bandwidth is unknown");
        check(listed.resolutions.contains("480×852"),"listed lower resolution");
        check(QualitySelector.available(unknown).incomplete,"unknown dimensions are reported");
        QualitySelector.Available mixed=QualitySelector.available(highOnly+"#EXT-X-STREAM-INF:BANDWIDTH=300\nunknown.m3u8\n");
        check(mixed.incomplete && mixed.resolutions.size()==1,"known and unknown variants cannot prove highest");
        boolean incomplete=false;try{QualitySelector.available(highOnly+"#EXT-X-STREAM-INF:BANDWIDTH=500");}catch(java.io.IOException expected){incomplete=true;}
        check(incomplete,"incomplete variant is rejected");
        boolean noMaster=false;try{QualitySelector.available("#EXTM3U\n#EXTINF:5,\nsegment.m4s");}catch(java.io.IOException expected){noMaster=true;}
        check(noMaster,"media playlist is not a master");
        System.out.println("QualitySelector tests passed");
    }
}
