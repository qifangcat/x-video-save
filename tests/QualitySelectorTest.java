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
        System.out.println("QualitySelector tests passed");
    }
}
