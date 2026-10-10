package org.kendar.dlna.platform;

import java.util.ArrayList;
import java.util.List;

/**
 * Path-level network access control and trusted-proxy handling (plan section 9), free of Jetty types.
 *
 * <ul>
 *   <li>Admin API and admin pages ({@code /api/v1/admin}, {@code /admin}) and the DLNA paths ({@code /upnp},
 *       {@code /stream}, {@code /art}) answer only clients on a LAN address; everything else (web app, user API,
 *       health) answers anywhere.</li>
 *   <li>{@code X-Forwarded-For} / {@code X-Forwarded-Proto} count only when the direct peer is a trusted proxy.</li>
 * </ul>
 */
public final class NetworkPolicy {

    /** Loopback, RFC 1918, link-local and unique-local ranges. */
    public static final List<String> DEFAULT_LAN = List.of("127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12",
            "192.168.0.0/16", "169.254.0.0/16", "::1/128", "fc00::/7", "fe80::/10");

    /** The effective client of a request. */
    public record Client(String address, boolean secure) {
    }

    private static final List<String> LAN_ONLY_PREFIXES = List.of("/api/v1/admin", "/admin", "/upnp", "/stream",
            "/art");

    private final List<Cidr> lan;
    private final List<Cidr> trusted;

    /** @param lan the LAN ranges (null/empty = {@link #DEFAULT_LAN}); @param trustedProxies none by default */
    public NetworkPolicy(List<String> lan, List<String> trustedProxies) {
        this.lan = parse(lan == null || lan.isEmpty() ? DEFAULT_LAN : lan);
        this.trusted = parse(trustedProxies == null ? List.of() : trustedProxies);
    }

    public static NetworkPolicy defaults() {
        return new NetworkPolicy(null, null);
    }

    /** @throws IllegalArgumentException when an entry is not an IP range */
    public static List<Cidr> parse(List<String> entries) {
        List<Cidr> out = new ArrayList<>();
        for (String e : entries) {
            out.add(Cidr.parse(e));
        }
        return out;
    }

    public boolean isLan(String address) {
        return lan.stream().anyMatch(c -> c.contains(address));
    }

    public boolean isTrustedProxy(String address) {
        return trusted.stream().anyMatch(c -> c.contains(address));
    }

    /** Whether the path may only be reached from the LAN. */
    public static boolean lanOnly(String path) {
        if (path == null) {
            return false;
        }
        for (String p : LAN_ONLY_PREFIXES) {
            if (path.equals(p) || path.startsWith(p + "/")) {
                return true;
            }
        }
        return false;
    }

    /** Whether a client on {@code address} may request {@code path}. */
    public boolean allows(String path, String address) {
        return !lanOnly(path) || isLan(address);
    }

    /**
     * The effective client. Forwarded headers are honored only when {@code peer} is a trusted proxy: the
     * {@code X-Forwarded-For} chain is walked from the right, skipping trusted proxies, and the first other address
     * is the client; {@code X-Forwarded-Proto: https} marks the connection secure.
     */
    public Client resolve(String peer, boolean peerSecure, String forwardedFor, String forwardedProto) {
        if (!isTrustedProxy(peer)) {
            return new Client(peer, peerSecure);
        }
        String client = peer;
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            String[] hops = forwardedFor.split(",");
            for (int i = hops.length - 1; i >= 0; i--) {
                String hop = hops[i].strip();
                if (Cidr.literal(hop) == null) {
                    break;   // garbage: stop at the last trustworthy hop
                }
                client = hop;
                if (!isTrustedProxy(hop)) {
                    break;
                }
            }
        }
        boolean secure = peerSecure || (forwardedProto != null
                && "https".equalsIgnoreCase(forwardedProto.split(",")[0].strip()));
        return new Client(client, secure);
    }
}
