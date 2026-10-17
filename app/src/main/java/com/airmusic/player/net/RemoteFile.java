package com.airmusic.player.net;

/** 网络路径里的一项（文件或目录）。 */
public final class RemoteFile {

    /** 文件/目录名（不含路径）。 */
    public final String name;
    public final boolean directory;
    public final long size;

    public RemoteFile(String name, boolean directory, long size) {
        this.name = name;
        this.directory = directory;
        this.size = size;
    }
}
