package com.airmusic.player.multicast;

import android.util.Log;

import com.airmusic.player.util.DiagnosticLog;
import nz.co.iswe.android.airplay.AirPlayServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

/**
 * mDNS registration + discovery for multi-room devices using JmDNS. The
 * service instance name is the device's AirPlay name, so the receiver list
 * shows the same names users already know.
 */
public class MultiRoomDiscovery {

    public interface Listener {
        void onDevicesChanged();
    }

    public static class DeviceInfo {
        public final String name;
        public final String[] addresses;
        public final int port;

        DeviceInfo(String name, String[] addresses, int port) {
            this.name = name;
            this.addresses = addresses;
            this.port = port;
        }
    }

    private static final String TAG = "MultiRoomDiscovery";

    private final Map<String, DeviceInfo> devices = new ConcurrentHashMap<>();
    private final List<Listener> listeners = new ArrayList<>();
    /** JmDNS instances this discovery is already listening on. */
    private final java.util.Set<JmDNS> attached =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private volatile JmDNS jmdns;
    /** Name we announced; empty until the registration actually succeeded. */
    private volatile String registeredName = "";
    private volatile String lastRequestedName = "";
    private volatile boolean registering;
    private volatile List<InetAddress> localAddresses;

    private boolean isLocalAddress(String host) {
        try {
            if (localAddresses == null) {
                List<InetAddress> addrs = new ArrayList<>();
                for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    addrs.addAll(Collections.list(ni.getInetAddresses()));
                }
                localAddresses = addrs;
            }
            InetAddress addr = InetAddress.getByName(host);
            for (InetAddress l : localAddresses) {
                if (l.equals(addr)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public synchronized void addListener(Listener l) {
        listeners.add(l);
    }

    public synchronized void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyChanged() {
        List<Listener> copy;
        synchronized (this) {
            copy = new ArrayList<>(listeners);
        }
        for (Listener l : copy) {
            try {
                l.onDevicesChanged();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Registers this device's multi-room service (async).
     *
     * <p>Deliberately repeatable: a TV box usually boots before its network is
     * up, so the first attempts can fail because no JmDNS instance exists yet.
     * The old code gave up after ten seconds and never tried again, which is why
     * the desktop only saw multi-room devices after someone opened the device
     * dialog on the box (that triggered a fresh scan).
     */
    public void register(final String deviceName) {
        lastRequestedName = deviceName == null ? "" : deviceName;
        if (registering || isHealthy()) return;
        registering = true;
        Thread t = new Thread(() -> {
            try {
                // The AirPlay server owns the JmDNS instances (one per interface);
                // register on them so the service is announced on every usable
                // interface without port-5353 conflicts. The instances appear a
                // moment after the AirPlay engine starts, so retry briefly.
                for (int attempt = 0; attempt < 20; attempt++) {
                    try {
                        int n = AirPlayServer.getIstance().registerAuxiliaryService(
                                MultiRoomProtocol.SERVICE_TYPE, lastRequestedName,
                                MultiRoomProtocol.PORT,
                                java.util.Collections.singletonMap("name", lastRequestedName));
                        Log.i(TAG, "register attempt " + attempt + " -> " + n + " interface(s)");
                        if (n > 0) {
                            attachListeners();
                            registeredName = lastRequestedName;
                            Log.i(TAG, "registered multi-room service '" + lastRequestedName
                                    + "' on " + n + " interface(s)");
                            return;
                        }
                    } catch (Throwable e) {
                        Log.w(TAG, "registration failed", e);
                    }
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                }
                Log.w(TAG, "multi-room registration: no JmDNS available yet (watcher will retry)");
            } finally {
                registering = false;
            }
        }, "mr-register");
        t.setDaemon(true);
        t.start();
    }

    /** Hooks the service listener onto every live JmDNS instance (once each). */
    private void attachListeners() {
        try {
            for (JmDNS j : AirPlayServer.getIstance().getJmDNSInstances()) {
                if (j == null || attached.contains(j)) continue;
                j.addServiceListener(MultiRoomProtocol.SERVICE_TYPE, serviceListener);
                attached.add(j);
                jmdns = j;
            }
        } catch (Throwable ignored) {
        }
    }

    /** True when our service is announced and the listener is on a live JmDNS. */
    public boolean isHealthy() {
        if (registeredName.isEmpty()) return false;
        JmDNS j = jmdns;
        if (j == null) return false;
        try {
            return AirPlayServer.getIstance().getJmDNSInstances().contains(j);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Watchdog entry point: brings discovery back up when the network appeared
     * after the app started, or when the AirPlay mDNS stack was rebuilt (the
     * service restarts it after a late network on boot).
     */
    public void ensureRegistered() {
        if (isHealthy()) return;
        Log.i(TAG, "multi-room discovery is stale (name='" + lastRequestedName
                + "') - re-registering");
        DiagnosticLog.i(TAG, "multi-room discovery stale - re-registering");
        // The instance we knew about is gone (or never existed): start over.
        jmdns = null;
        registeredName = "";
        attached.clear();
        register(lastRequestedName);
        rescan();
    }

    private final ServiceListener serviceListener = new ServiceListener() {
        @Override
        public void serviceAdded(ServiceEvent event) {
            ServiceInfo info = event.getInfo();
            if (info != null) {
                JmDNS j = jmdns;
                if (j != null) j.requestServiceInfo(MultiRoomProtocol.SERVICE_TYPE, info.getName());
            }
        }

        @Override
        public void serviceRemoved(ServiceEvent event) {
            devices.remove(event.getName());
            notifyChanged();
        }

        @Override
        public void serviceResolved(ServiceEvent event) {
            addDevice(event.getInfo());
        }
    };

    private void addDevice(ServiceInfo info) {
        if (info == null) return;
        String[] addresses = info.getHostAddresses();
        if (addresses == null || addresses.length == 0) return;
        // Order addresses so IPv4 comes first, link-local IPv6 comes last
        // (link-local needs a scope id and usually fails to connect).
        String[] ordered = orderAddresses(addresses);
        String host = ordered[0];
        // Never list this device itself.
        if (isLocalAddress(host)) return;
        int port = info.getPort() > 0 ? info.getPort() : MultiRoomProtocol.PORT;
        // JmDNS renames duplicate registrations to "name (1)", "name (2)", ...
        // (stale entries from earlier versions may still be cached), so
        // normalize the name for deduplication.
        String name = normalizeName(info.getName());
        DeviceInfo existing = devices.get(name);
        if (existing == null) {
            devices.put(name, new DeviceInfo(name, ordered, port));
        } else {
            devices.put(name, new DeviceInfo(name, mergeAddresses(existing.addresses, ordered), port));
        }
        notifyChanged();
    }

    /** IPv4 first, then non-link-local IPv6, then link-local IPv6. */
    private static String[] orderAddresses(String[] addresses) {
        List<String> ipv4 = new ArrayList<>();
        List<String> global6 = new ArrayList<>();
        List<String> linkLocal6 = new ArrayList<>();
        for (String a : addresses) {
            if (a.contains(".")) {
                ipv4.add(a);
            } else if (a.startsWith("fe8")) {
                linkLocal6.add(a);
            } else {
                global6.add(a);
            }
        }
        List<String> out = new ArrayList<>();
        out.addAll(ipv4);
        out.addAll(global6);
        out.addAll(linkLocal6);
        return out.toArray(new String[0]);
    }

    private static String[] mergeAddresses(String[] a, String[] b) {
        List<String> out = new ArrayList<>();
        for (String s : a) {
            if (!out.contains(s)) out.add(s);
        }
        for (String s : b) {
            if (!out.contains(s)) out.add(s);
        }
        return out.toArray(new String[0]);
    }

    /** Strips a trailing " (N)" suffix JmDNS adds to duplicate instances. */
    private static String normalizeName(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.*?)\\s+\\(\\d+\\)$").matcher(t);
        if (m.matches()) return m.group(1);
        return t;
    }

    /** Re-scans the network (used when the user opens the device dialog). */
    public void rescan() {
        Thread t = new Thread(() -> {
            try {
                // Make sure we are listening on whatever mDNS stack is alive now:
                // this is what makes the list appear when the network came up
                // late (or after the AirPlay service was restarted).
                attachListeners();
                if (jmdns != null && registeredName.isEmpty()) {
                    registeredName = lastRequestedName;
                }
                devices.clear();
                List<JmDNS> instances = AirPlayServer.getIstance().getJmDNSInstances();
                for (JmDNS j : instances) {
                    ServiceInfo[] infos = j.list(MultiRoomProtocol.SERVICE_TYPE, 1500);
                    for (ServiceInfo info : infos) {
                        Log.i(TAG, "raw service seen: '" + info.getName() + "' @" +
                                (info.getHostAddresses() != null && info.getHostAddresses().length > 0
                                        ? info.getHostAddresses()[0] : "?"));
                        addDevice(info);
                    }
                }
                notifyChanged();
            } catch (Exception e) {
                Log.w(TAG, "rescan failed", e);
            }
        }, "mr-rescan");
        t.setDaemon(true);
        t.start();
    }

    /** Devices seen on the network (excluding this device if it is registered). */
    public List<DeviceInfo> getDevices() {
        List<DeviceInfo> out = new ArrayList<>(devices.values());
        return out;
    }

    public void stop() {
        jmdns = null;
        registeredName = "";
        attached.clear();
    }
}
