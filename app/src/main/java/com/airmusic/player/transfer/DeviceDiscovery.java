package com.airmusic.player.transfer;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Answers the desktop app's UDP broadcast so devices show up automatically.
 *
 * <p>The companion app sends {@code BUKAMUSIC_DISCOVER} to the broadcast
 * address on {@link #PORT}; every device answers with the same JSON payload as
 * {@code GET /api/info} plus its HTTP port. UDP is used instead of mDNS because
 * it works the same on Windows, Linux and macOS without extra services.
 */
public final class DeviceDiscovery {

    public static final int PORT = 47101;
    public static final String REQUEST = "BUKAMUSIC_DISCOVER";

    private static final String TAG = "DeviceDiscovery";
    private static final int MAX_PACKET = 2048;

    private final Context context;
    private final MusicTransferServer server;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;
    private DatagramSocket socket;

    DeviceDiscovery(Context context, MusicTransferServer server) {
        this.context = context.getApplicationContext();
        this.server = server;
    }

    void start() {
        if (running.get()) return;
        running.set(true);
        thread = new Thread(this::run, "device-discovery");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running.set(false);
        if (socket != null) {
            socket.close();
            socket = null;
        }
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    private void run() {
        try {
            socket = new DatagramSocket(PORT);
            socket.setBroadcast(true);
            Log.i(TAG, "discovery listening on udp/" + PORT);
            byte[] buffer = new byte[MAX_PACKET];
            while (running.get()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String request = new String(packet.getData(), packet.getOffset(),
                        packet.getLength(), StandardCharsets.UTF_8).trim();
                if (!request.startsWith(REQUEST)) continue;
                byte[] reply = replyPayload().getBytes(StandardCharsets.UTF_8);
                InetAddress address = packet.getAddress();
                int port = packet.getPort();
                socket.send(new DatagramPacket(reply, reply.length, address, port));
            }
        } catch (Throwable t) {
            if (running.get()) Log.w(TAG, "discovery stopped: " + t);
        }
    }

    private String replyPayload() {
        JSONObject json = ControlApi.infoJson(context);
        try {
            json.put("port", server == null ? 0 : server.getPort());
        } catch (Throwable ignored) {
        }
        return json.toString();
    }
}
