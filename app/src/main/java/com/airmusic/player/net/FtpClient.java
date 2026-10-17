package com.airmusic.player.net;

import com.airmusic.player.library.NetSource;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 极简 FTP 客户端（主动控制连接 + PASV 数据连接），够用来列目录和随机读取。
 *
 * <p>随机读取靠 REST + RETR：要跳到某个位置就重开一次数据连接并带上 REST；
 * 只是往前跳一点点（&lt; 1MB）则直接读掉，省一次往返。
 */
public final class FtpClient {

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 15000;

    private FtpClient() {
    }

    private static final class Session implements Closeable {
        final Socket control;
        final BufferedReader reader;
        final OutputStreamWriter writer;
        String baseDir;
        /** 最近一条响应的原文（SIZE 之类要取值时用）。 */
        String lastLine = "";

        Session(NetSource source) throws IOException {
            control = new Socket();
            control.connect(new InetSocketAddress(source.host, source.effectivePort()),
                    CONNECT_TIMEOUT_MS);
            control.setSoTimeout(READ_TIMEOUT_MS);
            reader = new BufferedReader(
                    new InputStreamReader(control.getInputStream(), StandardCharsets.ISO_8859_1));
            writer = new OutputStreamWriter(control.getOutputStream(), StandardCharsets.ISO_8859_1);
            readReply();
            String user = source.hasCredentials() ? source.user : "anonymous";
            String pass = source.hasCredentials()
                    ? (source.password == null ? "" : source.password) : "anonymous@";
            send("USER " + user);
            int code = readReply();
            if (code == 331 || code == 332) {
                send("PASS " + pass);
                code = readReply();
            }
            if (code >= 400) throw new IOException("FTP 登录失败：" + code);
            command("TYPE I");
            command("OPTS UTF8 ON"); // 不支持也无妨
            baseDir = source.path == null ? "" : source.path.trim();
        }

        private void send(String line) throws IOException {
            writer.write(line + "\r\n");
            writer.flush();
        }

        /** 读一条完整响应（处理 "123-..." 多行形式），返回状态码。 */
        int readReply() throws IOException {
            String line = reader.readLine();
            if (line == null) throw new IOException("FTP 连接已断开");
            int code = parseCode(line);
            if (line.length() > 3 && line.charAt(3) == '-') {
                while (true) {
                    String next = reader.readLine();
                    if (next == null) break;
                    if (next.length() >= 4 && next.charAt(3) == ' '
                            && parseCode(next) == code) {
                        line = next;
                        break;
                    }
                }
            }
            lastLine = line;
            return code;
        }

        private static int parseCode(String line) {
            try {
                return Integer.parseInt(line.substring(0, 3));
            } catch (Throwable t) {
                return -1;
            }
        }

        /** 执行命令并返回状态码。 */
        int command(String line) throws IOException {
            send(line);
            return readReply();
        }

        /** PASV：返回数据连接。 */
        Socket passive() throws IOException {
            send("PASV");
            String line = reader.readLine();
            if (line == null) throw new IOException("FTP 连接已断开");
            int open = line.indexOf('(');
            int close = line.indexOf(')', open + 1);
            if (open < 0 || close < 0) throw new IOException("FTP PASV 响应异常：" + line);
            String[] parts = line.substring(open + 1, close).split(",");
            if (parts.length < 6) throw new IOException("FTP PASV 响应异常：" + line);
            String host = parts[0].trim() + "." + parts[1].trim() + "." + parts[2].trim()
                    + "." + parts[3].trim();
            int port = Integer.parseInt(parts[4].trim()) * 256 + Integer.parseInt(parts[5].trim());
            Socket data = new Socket();
            data.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            data.setSoTimeout(READ_TIMEOUT_MS);
            return data;
        }

        String pathOf(String relative) {
            String rel = relative == null ? "" : relative;
            if (baseDir.isEmpty()) return rel;
            if (rel.isEmpty()) return baseDir;
            return baseDir.endsWith("/") ? baseDir + rel : baseDir + "/" + rel;
        }

        @Override
        public void close() {
            try {
                send("QUIT");
            } catch (Throwable ignored) {
            }
            try {
                control.close();
            } catch (Throwable ignored) {
            }
        }
    }

    public static List<RemoteFile> list(NetSource source, String relativePath) throws IOException {
        Session session = new Session(source);
        try {
            List<RemoteFile> out = new ArrayList<>();
            String path = session.pathOf(relativePath);
            Socket data = session.passive();
            // 先试 MLSD（机器可读），失败再退回 LIST
            int code = session.command("MLSD " + (path.isEmpty() ? "." : path));
            boolean mlsd = code < 400;
            String text;
            if (mlsd) {
                text = readAll(data);
                session.readReply(); // 226
            } else {
                // 服务器不认识 MLSD（vsftpd 常见）：这时数据连接上没有数据，
                // 直接读会一直等到超时。先关掉它，再用 LIST 重新开一条。
                closeQuietly(data);
                text = null;
            }
            if (!mlsd) {
                data = session.passive();
                code = session.command("LIST " + (path.isEmpty() ? "." : path));
                if (code >= 400) throw new IOException("FTP 列目录失败：" + code);
                text = readAll(data);
                session.readReply();
            }
            for (String line : text.split("\r?\n")) {
                if (line.trim().isEmpty()) continue;
                RemoteFile file = mlsd ? parseMlsd(line) : parseList(line);
                if (file != null) out.add(file);
            }
            return out;
        } finally {
            session.close();
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (Throwable ignored) {
        }
    }

    private static RemoteFile parseMlsd(String line) {
        // 形如：type=file;size=1234; name
        int space = line.indexOf(' ');
        if (space <= 0) return null;
        String facts = line.substring(0, space);
        String name = line.substring(space + 1).trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) return null;
        boolean directory = facts.toLowerCase(Locale.ROOT).contains("type=dir");
        long size = 0;
        for (String fact : facts.split(";")) {
            if (fact.toLowerCase(Locale.ROOT).startsWith("size=")) {
                try {
                    size = Long.parseLong(fact.substring(5).trim());
                } catch (Throwable ignored) {
                }
            }
        }
        return new RemoteFile(name, directory, size);
    }

    private static RemoteFile parseList(String line) {
        // 形如：drwxr-xr-x 2 owner group 4096 Jan 01 12:00 name
        String[] parts = line.trim().split("\\s+", 9);
        if (parts.length < 9) return null;
        String name = parts[8].trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) return null;
        if (name.contains(" -> ")) name = name.substring(0, name.indexOf(" -> "));
        boolean directory = parts[0].startsWith("d");
        long size = 0;
        try {
            size = Long.parseLong(parts[4]);
        } catch (Throwable ignored) {
        }
        return new RemoteFile(name, directory, size);
    }

    private static String readAll(Socket data) throws IOException {
        try {
            InputStream in = data.getInputStream();
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) buffer.write(chunk, 0, read);
            return buffer.toString("UTF-8");
        } finally {
            try {
                data.close();
            } catch (Throwable ignored) {
            }
        }
    }

    public static NetStream open(final NetSource source, final String relativePath) throws IOException {
        final Session session = new Session(source);
        final String path = session.pathOf(relativePath);
        long length = -1;
        try {
            int code = session.command("SIZE " + path);
            if (code == 213) {
                length = Long.parseLong(session.lastLine.trim());
            }
        } catch (Throwable ignored) {
        }
        final long total = length;
        return new NetStream() {
            private Socket data;
            private InputStream in;
            private long position;

            private void openAt(long target) throws IOException {
                closeData();
                Socket socket = session.passive();
                if (target > 0) {
                    int code = session.command("REST " + target);
                    if (code >= 400) throw new IOException("FTP REST 失败：" + code);
                }
                int code = session.command("RETR " + path);
                if (code >= 400) {
                    try {
                        socket.close();
                    } catch (Throwable ignored) {
                    }
                    throw new IOException("FTP 下载失败：" + code);
                }
                data = socket;
                in = socket.getInputStream();
                position = target;
            }

            private void closeData() {
                try {
                    if (data != null) data.close();
                } catch (Throwable ignored) {
                }
                data = null;
                in = null;
            }

            @Override
            public long length() {
                return total;
            }

            @Override
            public void seek(long target) throws IOException {
                long wanted = Math.max(0, target);
                if (in != null && wanted == position) return;
                if (in != null && wanted > position && wanted - position < 1024 * 1024) {
                    long remaining = wanted - position;
                    byte[] skip = new byte[8192];
                    while (remaining > 0) {
                        int read = in.read(skip, 0, (int) Math.min(skip.length, remaining));
                        if (read <= 0) break;
                        remaining -= read;
                        position += read;
                    }
                    if (remaining <= 0) return;
                }
                openAt(wanted);
            }

            @Override
            public int read(byte[] buffer, int offset, int count) throws IOException {
                if (in == null) openAt(position);
                int read = in.read(buffer, offset, count);
                if (read > 0) position += read;
                return read;
            }

            @Override
            public void close() {
                closeData();
                try {
                    session.readReply();
                } catch (Throwable ignored) {
                }
                session.close();
            }
        };
    }
}
