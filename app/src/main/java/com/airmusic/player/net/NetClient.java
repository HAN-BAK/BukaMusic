package com.airmusic.player.net;

import com.airmusic.player.library.NetSource;

import java.io.IOException;
import java.util.List;

/** 网络音乐路径的统一入口：按类型分发到 WebDAV / SMB / FTP。 */
public final class NetClient {

    private NetClient() {
    }

    public static List<RemoteFile> list(NetSource source, String relativePath) throws IOException {
        String type = source.type == null ? "" : source.type.toLowerCase(java.util.Locale.ROOT);
        switch (type) {
            case NetSource.TYPE_WEBDAV:
                return WebDavClient.list(source, relativePath);
            case NetSource.TYPE_FTP:
                return FtpClient.list(source, relativePath);
            default:
                return SmbClient.list(source, relativePath);
        }
    }

    public static NetStream open(NetSource source, String relativePath) throws IOException {
        String type = source.type == null ? "" : source.type.toLowerCase(java.util.Locale.ROOT);
        switch (type) {
            case NetSource.TYPE_WEBDAV:
                return WebDavClient.open(source, relativePath);
            case NetSource.TYPE_FTP:
                return FtpClient.open(source, relativePath);
            default:
                return SmbClient.open(source, relativePath);
        }
    }

    /** 连接测试：列一次发起目录。 */
    public static void test(NetSource source) throws IOException {
        if (NetSource.TYPE_SMB.equalsIgnoreCase(source.type)) {
            SmbClient.test(source);
            return;
        }
        list(source, "");
    }
}
