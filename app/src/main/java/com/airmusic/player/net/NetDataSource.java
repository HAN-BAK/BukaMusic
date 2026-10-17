package com.airmusic.player.net;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import com.airmusic.player.library.NetSource;
import com.airmusic.player.util.Prefs;

import java.io.IOException;

/**
 * 播放网络音乐用的数据源：把 {@code buka-net://<sourceId>/<相对路径>} 解析成
 * 真实的 WebDAV / SMB / FTP 读取，其它 scheme（file / content / http …）原样交给
 * 默认数据源处理。
 *
 * <p>凭证不进 uri：uri 里只有源 id，账号密码在 {@link Prefs} 里按 id 查回来。
 */
public final class NetDataSource implements DataSource {

    private final Context context;
    private final DataSource fallback;

    private NetStream stream;
    private Uri uri;
    private long bytesRemaining;
    private boolean opened;
    private boolean delegating;

    public NetDataSource(Context context, DataSource fallback) {
        this.context = context.getApplicationContext();
        this.fallback = fallback;
    }

    /** 包一层：只接管 buka-net://，其余交给传入的工厂。 */
    public static DataSource.Factory wrap(final Context context, final DataSource.Factory base) {
        return () -> new NetDataSource(context, base.createDataSource());
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        this.uri = dataSpec.uri;
        if (!NetSource.SCHEME.equals(uri.getScheme())) {
            delegating = true;
            long length = fallback.open(dataSpec);
            opened = true;
            return length;
        }
        delegating = false;
        String sourceId = uri.getHost();
        String relativePath = uri.getPath() == null ? "" : uri.getPath();
        if (relativePath.startsWith("/")) relativePath = relativePath.substring(1);
        NetSource source = new Prefs(context).getNetSource(sourceId);
        if (source == null) {
            throw new IOException("网络音乐路径不存在（可能已被删除）");
        }
        stream = NetClient.open(source, relativePath);
        long position = dataSpec.position;
        if (position > 0) stream.seek(position);
        long length = stream.length();
        if (dataSpec.length != C.LENGTH_UNSET) {
            bytesRemaining = dataSpec.length;
        } else if (length >= 0) {
            bytesRemaining = length - position;
        } else {
            bytesRemaining = C.LENGTH_UNSET;
        }
        opened = true;
        return bytesRemaining;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (delegating) return fallback.read(buffer, offset, length);
        if (length == 0) return 0;
        if (bytesRemaining == 0) return C.RESULT_END_OF_INPUT;
        int toRead = bytesRemaining == C.LENGTH_UNSET
                ? length : (int) Math.min(length, bytesRemaining);
        int read = stream.read(buffer, offset, toRead);
        if (read == -1) return C.RESULT_END_OF_INPUT;
        if (bytesRemaining != C.LENGTH_UNSET) bytesRemaining -= read;
        return read;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() throws IOException {
        if (delegating) {
            fallback.close();
            opened = false;
            return;
        }
        if (stream != null) {
            try {
                stream.close();
            } catch (Throwable ignored) {
            }
            stream = null;
        }
        opened = false;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        // 本数据源不实现传输统计（进度条由播放器自己算位置）
    }

    public boolean isOpened() {
        return opened;
    }
}
