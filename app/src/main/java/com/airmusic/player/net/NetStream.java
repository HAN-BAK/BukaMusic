package com.airmusic.player.net;

import java.io.Closeable;
import java.io.IOException;

/**
 * 网络音乐的随机读取流：播放器会顺序读、也会 seek 到任意位置重读。
 *
 * <p>三种协议各自实现：WebDAV 用 HTTP Range，FTP 用 REST/RETR，SMB 用
 * SmbRandomAccessFile（本身就是随机读取）。
 */
public interface NetStream extends Closeable {

    /** 总长度；未知时返回 -1。 */
    long length();

    /** 移动到指定位置，下一次 read 从这里开始。 */
    void seek(long position) throws IOException;

    /** 读取若干字节；返回 -1 表示读完。 */
    int read(byte[] buffer, int offset, int length) throws IOException;
}
