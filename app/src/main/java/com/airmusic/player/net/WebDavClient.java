package com.airmusic.player.net;

import com.airmusic.player.library.NetSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * 极简 WebDAV 客户端：PROPFIND 列目录 + GET（带 Range）随机读取。
 *
 * <p>不用第三方库，HttpURLConnection 就够；地址支持 http:// 与 https://。
 */
public final class WebDavClient {

    private static final int TIMEOUT_MS = 8000;

    private WebDavClient() {
    }

    private static String baseUrl(NetSource source) {
        boolean https = source.port == 443;
        StringBuilder sb = new StringBuilder(https ? "https://" : "http://");
        sb.append(source.host).append(':').append(source.effectivePort());
        String base = source.path == null ? "" : source.path.trim();
        if (!base.isEmpty()) {
            if (!base.startsWith("/")) sb.append('/');
            sb.append(encodePath(base));
        }
        return sb.toString();
    }

    private static String urlOf(NetSource source, String relativePath) {
        String base = baseUrl(source);
        if (relativePath == null || relativePath.isEmpty()) return base;
        String rel = relativePath.startsWith("/") ? relativePath.substring(1) : relativePath;
        return base.endsWith("/") ? base + encodePath(rel) : base + "/" + encodePath(rel);
    }

    private static String encodePath(String path) {
        StringBuilder out = new StringBuilder();
        for (String part : path.split("/")) {
            if (part.isEmpty()) continue;
            if (out.length() > 0) out.append('/');
            out.append(android.net.Uri.encode(part));
        }
        return out.toString();
    }

    private static void applyAuth(HttpURLConnection conn, NetSource source) {
        if (!source.hasCredentials()) return;
        String token = source.user + ":" + (source.password == null ? "" : source.password);
        conn.setRequestProperty("Authorization",
                "Basic " + Base64.getEncoder().encodeToString(
                        token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /** 列一层目录（Depth: 1）。 */
    public static List<RemoteFile> list(NetSource source, String relativePath) throws IOException {
        String url = urlOf(source, relativePath);
        if (!url.endsWith("/")) url = url + "/";
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<d:propfind xmlns:d=\"DAV:\"><d:prop>"
                + "<d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>";
        // Android 的 HttpURLConnection 白名单里没有 PROPFIND（只会抛
        // "Expected one of [OPTIONS, GET, ...] but was PROPFIND"），所以自己用
        // socket 发这一条请求；GET/HEAD 仍走 HttpURLConnection。
        String xml = rawRequest(url, source, "PROPFIND", body);
        String normalized = normalizeNamespaces(xml);
        List<RemoteFile> parsed = parse(xml, new URL(url).getPath());
        android.util.Log.i("WebDavClient", "PROPFIND " + url + " → 响应 "
                + xml.length() + " 字符，规范化后含<response>=" + normalized.contains("<response")
                + "，解析到 " + parsed.size() + " 项；开头="
                + xml.substring(0, Math.min(100, xml.length())));
        return parsed;
    }

    /** 用原始 socket 发一条 HTTP/1.1 请求（支持 https，处理 chunked）。 */
    private static String rawRequest(String url, NetSource source,
                                     String method, String body) throws IOException {
        java.net.URL target = new java.net.URL(url);
        boolean https = "https".equalsIgnoreCase(target.getProtocol());
        String host = target.getHost();
        int port = target.getPort() > 0 ? target.getPort() : (https ? 443 : 80);
        java.net.Socket socket;
        if (https) {
            javax.net.ssl.SSLSocketFactory factory =
                    (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
            socket = factory.createSocket();
            socket.connect(new java.net.InetSocketAddress(host, port), TIMEOUT_MS);
            ((javax.net.ssl.SSLSocket) socket).startHandshake();
        } else {
            socket = new java.net.Socket();
            socket.connect(new java.net.InetSocketAddress(host, port), TIMEOUT_MS);
        }
        socket.setSoTimeout(TIMEOUT_MS);
        try {
            byte[] bodyBytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String path = target.getFile();
            if (path == null || path.isEmpty()) path = "/";
            StringBuilder head = new StringBuilder();
            head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            head.append("Host: ").append(host);
            if ((https ? 443 : 80) != port) head.append(':').append(port);
            head.append("\r\nConnection: close\r\n");
            head.append("Depth: 1\r\n");
            head.append("Content-Type: application/xml; charset=utf-8\r\n");
            if (source.hasCredentials()) {
                String token = source.user + ":" + (source.password == null ? "" : source.password);
                head.append("Authorization: Basic ")
                        .append(Base64.getEncoder().encodeToString(
                                token.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .append("\r\n");
            }
            head.append("Content-Length: ").append(bodyBytes.length).append("\r\n\r\n");
            java.io.OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            out.write(bodyBytes);
            out.flush();
            return readHttpResponse(socket.getInputStream());
        } finally {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 读 HTTP 响应：拿状态码、去掉响应头，body 支持 Content-Length 与 chunked。 */
    private static String readHttpResponse(InputStream rawIn) throws IOException {
        java.io.BufferedInputStream in = new java.io.BufferedInputStream(rawIn);
        String status = readLine(in);
        if (status == null) throw new IOException("WebDAV 没有响应");
        int code = 0;
        int space = status.indexOf(' ');
        if (space > 0) {
            try {
                code = Integer.parseInt(status.substring(space + 1, space + 4));
            } catch (Throwable ignored) {
            }
        }
        long contentLength = -1;
        boolean chunked = false;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("content-length:")) {
                try {
                    contentLength = Long.parseLong(line.substring(15).trim());
                } catch (Throwable ignored) {
                }
            } else if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                chunked = true;
            }
        }
        if (code >= 400) throw new IOException("WebDAV " + code);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        if (chunked) {
            while (true) {
                String sizeLine = readLine(in);
                if (sizeLine == null || sizeLine.isEmpty()) break;
                int size;
                try {
                    size = Integer.parseInt(sizeLine.trim().split(";")[0], 16);
                } catch (Throwable t) {
                    break;
                }
                if (size <= 0) {
                    readLine(in);
                    break;
                }
                byte[] chunk = new byte[size];
                int offset = 0;
                while (offset < size) {
                    int read = in.read(chunk, offset, size - offset);
                    if (read <= 0) break;
                    offset += read;
                }
                out.write(chunk, 0, offset);
                readLine(in);
            }
        } else if (contentLength >= 0) {
            byte[] buffer = new byte[8192];
            long remaining = contentLength;
            while (remaining > 0) {
                int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read <= 0) break;
                out.write(buffer, 0, read);
                remaining -= read;
            }
        } else {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        }
        return out.toString("UTF-8");
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        if (c < 0 && sb.length() == 0) return null;
        return sb.toString();
    }

    /** 解析 multi-status：按 response 逐个取 href / resourcetype / getcontentlength。 */
    private static List<RemoteFile> parse(String xml, String basePath) {
        // 先去掉命名空间前缀（<d:response> → <response>、</D:href> → </href>），
        // 后面就能用最简单的字符串匹配。服务器前缀五花八门（d:/D:/lp1: 甚至没有），
        // 之前按"前缀感知"匹配写错了边界，扫描线程会死循环。
        String normalized = normalizeNamespaces(xml);
        List<RemoteFile> out = new ArrayList<>();
        int index = 0;
        while (true) {
            int start = normalized.indexOf("<response", index);
            if (start < 0) break;
            int end = normalized.indexOf("</response", start);
            if (end < 0) break;
            String block = normalized.substring(start, end);
            index = end + "</response".length();

            String href = tagValue(block, "href");
            if (href == null) continue;
            String decoded;
            try {
                decoded = android.net.Uri.decode(href);
            } catch (Throwable t) {
                decoded = href;
            }
            if (decoded.endsWith("/")) decoded = decoded.substring(0, decoded.length() - 1);
            String name = decoded;
            int slash = decoded.lastIndexOf('/');
            if (slash >= 0) name = decoded.substring(slash + 1);
            if (name.isEmpty()) continue;
            // 跳过目录自身（PROPFIND 会把请求的那一层也返回）
            String selfPath = basePath != null && basePath.endsWith("/")
                    ? basePath.substring(0, basePath.length() - 1) : basePath;
            if (selfPath != null && decoded.equals(selfPath)) continue;

            boolean directory = indexOfIgnoreCase(block, "<collection", 0) >= 0
                    || indexOfIgnoreCase(block, ":collection", 0) >= 0;
            long size = 0;
            String lengthText = tagValue(block, "getcontentlength");
            if (lengthText != null) {
                try {
                    size = Long.parseLong(lengthText.trim());
                } catch (Throwable ignored) {
                }
            }
            out.add(new RemoteFile(name, directory, size));
        }
        return out;
    }

    /**
     * 去掉命名空间前缀：{@code <d:response>} → {@code <response>}，
     * {@code </D:href>} → {@code </href>}。
     *
     * <p>用 lookbehind 而不是 {@code "<$1"} 分组引用——实测 Android 的
     * String.replaceAll 对"未参与匹配的可选分组"($1 为空) 处理与桌面 JDK 不同，
     * 会把整段原样留下，导致后面一个标签都匹配不到。
     */
    private static String normalizeNamespaces(String xml) {
        String out = xml.replaceAll("(?<=<)[A-Za-z0-9._-]+:", "");
        out = out.replaceAll("(?<=</)[A-Za-z0-9._-]+:", "");
        return out;
    }

    private static int indexOfIgnoreCase(String text, String needle, int from) {
        return text.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT), from);
    }

    private static String tagValue(String block, String localName) {
        String lower = block.toLowerCase(Locale.ROOT);
        String needle = localName.toLowerCase(Locale.ROOT);
        int nameStart = lower.indexOf(needle);
        while (nameStart >= 0) {
            // 必须是 ">xxx:localName" 这种结尾形式（避免匹配到别的标签名）
            int open = block.lastIndexOf('<', nameStart);
            int close = block.indexOf('>', nameStart);
            if (open >= 0 && close > open) {
                String tag = block.substring(open + 1, close);
                if (tag.endsWith(":" + localName) || tag.equalsIgnoreCase(localName)) {
                    int valueStart = close + 1;
                    int valueEnd = block.indexOf('<', valueStart);
                    if (valueEnd < 0) return null;
                    return block.substring(valueStart, valueEnd).trim();
                }
            }
            nameStart = lower.indexOf(needle, nameStart + 1);
        }
        return null;
    }

    /** 打开一个可随机读取的流。 */
    public static NetStream open(final NetSource source, final String relativePath) throws IOException {
        final String url = urlOf(source, relativePath);
        // 先探一次长度（HEAD）
        long length = -1;
        try {
            HttpURLConnection head = (HttpURLConnection) new URL(url).openConnection();
            head.setRequestMethod("HEAD");
            applyAuth(head, source);
            head.setConnectTimeout(TIMEOUT_MS);
            head.setReadTimeout(TIMEOUT_MS);
            if (head.getResponseCode() < 400) length = head.getContentLengthLong();
            head.disconnect();
        } catch (Throwable ignored) {
        }
        final long total = length;
        return new NetStream() {
            private InputStream in;
            private long position;

            private void openAt(long pos) throws IOException {
                closeQuietly(in);
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                applyAuth(conn, source);
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);
                if (pos > 0) conn.setRequestProperty("Range", "bytes=" + pos + "-");
                int code = conn.getResponseCode();
                if (code >= 400) throw new IOException("WebDAV " + code);
                in = conn.getInputStream();
                if (pos > 0 && code == 200) {
                    // 服务器不支持 Range：自己丢掉前面这些字节
                    long skipped = 0;
                    while (skipped < pos) {
                        long step = in.skip(pos - skipped);
                        if (step <= 0) break;
                        skipped += step;
                    }
                }
                position = pos;
            }

            @Override
            public long length() {
                return total;
            }

            @Override
            public void seek(long target) throws IOException {
                if (target == position && in != null) return;
                openAt(Math.max(0, target));
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
                closeQuietly(in);
                in = null;
            }
        };
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (Throwable ignored) {
        }
    }
}
