package com.airmusic.player.net;

import com.airmusic.player.library.NetSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbRandomAccessFile;

/**
 * SMB/CIFS（Windows 共享、群晖等 NAS）客户端。
 *
 * <p>用 jcifs-ng：列目录 {@code listFiles()}，读取用 {@code SmbRandomAccessFile}（天生支持随机读取）。
 * 路径里必须带共享名，例如 {@code Music/无损}，第一个斜杠前的就是共享名。
 */
public final class SmbClient {

    /** 每个网络路径复用同一个 SMB 会话：递归扫描时每次 list 都新建会话会把 NAS 的
     * 会话/句柄耗尽（表现为 0xC000009A，STATUS_INSUFFICIENT_RESOURCES）。 */
    private static final java.util.Map<String, CIFSContext> CONTEXTS =
            new java.util.HashMap<>();

    private SmbClient() {
    }

    private static synchronized CIFSContext context(NetSource source) {
        String cacheKey = source.id + "|" + source.host + "|" + source.effectivePort()
                + "|" + source.user + "|" + source.password + "|" + source.domain;
        CIFSContext cached = CONTEXTS.get(cacheKey);
        if (cached != null) return cached;
        Properties props = new Properties();
        props.setProperty("jcifs.smb.client.minVersion", "SMB1");
        props.setProperty("jcifs.smb.client.maxVersion", "SMB311");
        props.setProperty("jcifs.smb.client.connTimeout", "8000");
        props.setProperty("jcifs.smb.client.responseTimeout", "12000");
        props.setProperty("jcifs.smb.client.soTimeout", "20000");
        props.setProperty("jcifs.smb.client.dfs.disabled", "true");
        CIFSContext base;
        try {
            base = new BaseContext(new PropertyConfiguration(props));
        } catch (Throwable t) {
            throw new IllegalStateException("SMB 初始化失败：" + t);
        }
        CIFSContext ctx = !source.hasCredentials() ? base.withAnonymousCredentials()
                : base.withCredentials(
                new NtlmPasswordAuthenticator(
                        source.domain == null || source.domain.isEmpty() ? null : source.domain,
                        source.user, source.password));
        CONTEXTS.put(cacheKey, ctx);
        return ctx;
    }

    private static String urlOf(NetSource source, String relativePath) {
        StringBuilder sb = new StringBuilder("smb://");
        sb.append(source.host);
        if (source.port > 0 && source.port != 445) sb.append(':').append(source.port);
        sb.append('/');
        String base = source.path == null ? "" : source.path.trim();
        if (!base.isEmpty()) {
            if (base.startsWith("/")) base = base.substring(1);
            sb.append(base);
        }
        String rel = relativePath == null ? "" : relativePath.trim();
        if (!rel.isEmpty()) {
            if (rel.startsWith("/")) rel = rel.substring(1);
            if (sb.charAt(sb.length() - 1) != '/') sb.append('/');
            sb.append(rel);
        }
        return sb.toString();
    }

    /** 连接测试：能列出发起目录就算通过。 */
    public static void test(NetSource source) throws IOException {
        SmbFile dir = new SmbFile(urlWithSlash(source, ""), context(source));
        if (!dir.exists()) throw new IOException("SMB 路径不存在：" + dir);
        dir.listFiles();
    }

    private static String urlWithSlash(NetSource source, String relativePath) {
        String url = urlOf(source, relativePath);
        return url.endsWith("/") ? url : url + "/";
    }

    public static List<RemoteFile> list(NetSource source, String relativePath) throws IOException {
        SmbFile[] files = new SmbFile(urlWithSlash(source, relativePath), context(source)).listFiles();
        List<RemoteFile> out = new ArrayList<>();
        if (files == null) return out;
        for (SmbFile file : files) {
            String name = file.getName();
            if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
            if (name.isEmpty()) continue;
            boolean directory = file.isDirectory();
            long size = 0;
            if (!directory) {
                try {
                    size = file.length();
                } catch (Throwable ignored) {
                }
            }
            out.add(new RemoteFile(name, directory, size));
        }
        return out;
    }

    public static NetStream open(NetSource source, String relativePath) throws IOException {
        SmbFile file = new SmbFile(urlOf(source, relativePath), context(source));
        final SmbRandomAccessFile raf = new SmbRandomAccessFile(file, "r");
        return new NetStream() {
            @Override
            public long length() {
                try {
                    return raf.length();
                } catch (Throwable t) {
                    return -1;
                }
            }

            @Override
            public void seek(long position) throws IOException {
                raf.seek(Math.max(0, position));
            }

            @Override
            public int read(byte[] buffer, int offset, int count) throws IOException {
                return raf.read(buffer, offset, count);
            }

            @Override
            public void close() {
                try {
                    raf.close();
                } catch (Throwable ignored) {
                }
            }
        };
    }
}
