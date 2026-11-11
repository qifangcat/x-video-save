package dev.local.xvideosave;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import org.mp4parser.BoxParser;
import org.mp4parser.ParsableBox;

/** Media samples are read from the original file; don't copy every mdat into temporary files. */
public final class SkipMediaDataBox implements ParsableBox {
    private long size;
    @Override public String getType(){return "mdat";}
    @Override public long getSize(){return size;}
    @Override public void parse(ReadableByteChannel input,ByteBuffer header,long contentSize,BoxParser parser)throws IOException {
        if(!(input instanceof FileChannel)||contentSize<0)throw new IOException("不支持的视频分片输入");
        FileChannel channel=(FileChannel)input;long start=channel.position();
        if(contentSize>channel.size()-start)throw new IOException("视频分片不完整");
        size=header.limit()+contentSize;channel.position(start+contentSize);
    }
    @Override public void getBox(WritableByteChannel output)throws IOException {throw new IOException("不支持直接复制媒体数据盒");}
}
