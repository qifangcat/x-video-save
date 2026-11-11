package dev.local.xvideosave;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** isoparser 1.9.56's Apache-2.0 box map, embedded for LSPosed class loaders. */
final class Mp4BoxMappings {
    private static final String DATA =
        "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";
    static Properties load() throws IOException {
        Properties p=new Properties();
        try(InputStream in=new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(DATA)))) { p.load(in); }
        p.setProperty("mdat",SkipMediaDataBox.class.getName());
        return p;
    }
}
