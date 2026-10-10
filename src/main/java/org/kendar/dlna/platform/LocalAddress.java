package org.kendar.dlna.platform;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** Picks the IPv4 address advertised in {@code res} URLs when none is configured. */
final class LocalAddress {

    private LocalAddress() {
    }

    /** The configured address, else the first usable site-local IPv4 (skipping docker/veth), else loopback. */
    static String resolve(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.strip();
        }
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String name = ni.getName();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual() || name.startsWith("docker")
                        || name.startsWith("veth") || name.startsWith("br-")) {
                    continue;
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (java.net.SocketException e) {
            // fall through to loopback
        }
        return "127.0.0.1";
    }

    static String baseUrl(String configured, int port) {
        return "http://" + resolve(configured) + ":" + port;
    }
}
