package nz.co.iswe.android.airplay.network;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Arrays;
import java.util.Collections;
import java.util.logging.Logger;

public class NetworkUtils {
	
	private static final Logger LOG = Logger.getLogger(NetworkUtils.class.getName());
	
	private static NetworkUtils instance;
	public static NetworkUtils getInstance(){
		if(instance == null){
			instance = new NetworkUtils();
		}
		return instance;
	}
	
	private NetworkUtils(){
		
	}
	
	
	/**
	 * Returns a suitable hardware address.
	 * 
	 * @return a MAC address
	 */
	public byte[] getHardwareAddress() {
		try {
			/* Search network interfaces for an interface with a valid, non-blocked hardware address */
	    	for(final NetworkInterface iface: Collections.list(NetworkInterface.getNetworkInterfaces())) {
	    		if (iface.isLoopback()){
	    			continue;
	    		}
	    		if (iface.isPointToPoint()){
	    			continue;
	    		}

	    		try {
		    		final byte[] ifaceMacAddress = iface.getHardwareAddress();
		    		if ((ifaceMacAddress != null) && (ifaceMacAddress.length == 6) && !isBlockedHardwareAddress(ifaceMacAddress)) {
		    			LOG.info("Hardware address is " + toHexString(ifaceMacAddress) + " (" + iface.getDisplayName() + ")");
		    	    	return Arrays.copyOfRange(ifaceMacAddress, 0, 6);
		    		}
	    		}
	    		catch (final Throwable e) {
	    			/* Ignore */
	    		}
	    	}
		}
		catch (final Throwable e) {
			/* Ignore */
		}

		/* 真实 wlan0 MAC（很多电视盒子能直接读 sysfs） */
		try {
			final byte[] mac = readWlan0Mac();
			if (mac != null) {
				LOG.info("Hardware address is " + toHexString(mac) + " (wlan0)");
				return mac;
			}
		}
		catch (final Throwable e) {
			/* Ignore */
		}

		/* 设备指纹：以前这里用 getLocalHost()，在安卓上基本都返回 127.0.0.1
		 * （7F0000010000），于是每台设备的 AirPlay 服务名完全相同，iOS 会把它们
		 * 当成同一台设备合并显示。改用「型号 + 固件指纹」的 hash，保证每台设备不同。 */
		final byte[] deviceId = deviceFingerprintId();
		LOG.info("Hardware address is " + toHexString(deviceId) + " (device fingerprint)");
		return deviceId;
	}

	/** 读 /sys/class/net/wlan0/address，拿不到或无效返回 null。 */
	private byte[] readWlan0Mac() {
		try (java.io.BufferedReader reader = new java.io.BufferedReader(
				new java.io.FileReader("/sys/class/net/wlan0/address"))) {
			final String line = reader.readLine();
			if (line == null) return null;
			final String[] parts = line.trim().split(":");
			if (parts.length != 6) return null;
			final byte[] mac = new byte[6];
			for (int i = 0; i < 6; i++) {
				mac[i] = (byte) Integer.parseInt(parts[i], 16);
			}
			return isBlockedHardwareAddress(mac) ? null : mac;
		}
		catch (final Throwable e) {
			return null;
		}
	}

	/** 型号 + 固件指纹派生出的 6 字节设备标识（同一台设备每次运行都相同）。 */
	private byte[] deviceFingerprintId() {
		final String source = android.os.Build.MODEL + "|" + android.os.Build.DEVICE + "|"
				+ android.os.Build.BRAND + "|" + android.os.Build.FINGERPRINT + "|"
				+ android.os.Build.SERIAL;
		int hash = source.hashCode();
		final byte[] id = new byte[6];
		for (int i = 0; i < 6; i++) {
			id[i] = (byte) (hash >>> (8 * (i % 4)));
		}
		return id;
	}
	
	/**
	 * Converts an array of bytes to a hexadecimal string
	 * 
	 * @param bytes array of bytes
	 * @return hexadecimal representation
	 */
	private String toHexString(final byte[] bytes) {
		final StringBuilder s = new StringBuilder();
		for(final byte b: bytes) {
			final String h = Integer.toHexString(0x100 | b);
			s.append(h.substring(h.length() - 2, h.length()).toUpperCase());
		}
		return s.toString();
	}
	
	/**
	 * Decides whether or nor a given MAC address is the address of some
	 * virtual interface, like e.g. VMware's host-only interface (server-side).
	 * 
	 * @param addr a MAC address
	 * @return true if the MAC address is unsuitable as the device's hardware address
	 */
	public boolean isBlockedHardwareAddress(final byte[] addr) {
		if ((addr[0] & 0x02) != 0)
			/* Locally administered */
			return true;
		else if ((addr[0] == 0x00) && (addr[1] == 0x50) && (addr[2] == 0x56))
			/* VMware */
			return true;
		else if ((addr[0] == 0x00) && (addr[1] == 0x1C) && (addr[2] == 0x42))
			/* Parallels */
			return true;
		else if ((addr[0] == 0x00) && (addr[1] == 0x25) && (addr[2] == (byte)0xAE))
			/* Microsoft */
			return true;
		else
			return false;
	}

	public String getHostUtils() {
		try {
			final String model = android.os.Build.MODEL;
			if (model != null && model.trim().length() > 0) {
				return model.trim();
			}
		}
		catch (final Throwable e) {
			// fall through
		}
		return "Android-AirPlay";
	}

	public String getHardwareAddressString() {
		byte[] hardwareAddressBytes = getHardwareAddress();
		return toHexString(hardwareAddressBytes);
	}
	
}
