package org.kendar.dlna.platform;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/** An IPv4/IPv6 address range ({@code 10.0.0.0/8}) or a single address; never triggers DNS. */
public record Cidr(byte[] network, int prefix) {

    /** @throws IllegalArgumentException for text that is not an IP literal or has a bad prefix length */
    public static Cidr parse(String text) {
        String s = text == null ? "" : text.strip();
        int slash = s.indexOf('/');
        String addr = slash < 0 ? s : s.substring(0, slash);
        byte[] bytes = literal(addr);
        if (bytes == null) {
            throw new IllegalArgumentException("not an IP address or range: '" + text + "'");
        }
        int max = bytes.length * 8;
        int prefix = max;
        if (slash >= 0) {
            try {
                prefix = Integer.parseInt(s.substring(slash + 1).strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad prefix length in '" + text + "'");
            }
            if (prefix < 0 || prefix > max) {
                throw new IllegalArgumentException("bad prefix length in '" + text + "'");
            }
        }
        return new Cidr(bytes, prefix);
    }

    /** Parses an IP literal (v4 or v6, optional brackets / zone ignored); null when it is not one. */
    public static byte[] literal(String text) {
        if (text == null) {
            return null;
        }
        String a = text.strip();
        if (a.startsWith("[") && a.endsWith("]")) {
            a = a.substring(1, a.length() - 1);
        }
        int zone = a.indexOf('%');
        if (zone >= 0) {
            a = a.substring(0, zone);
        }
        boolean v4 = a.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        boolean v6 = a.indexOf(':') >= 0 && a.matches("[0-9a-fA-F:.]+");
        if (!v4 && !v6) {
            return null;
        }
        try {
            byte[] b = InetAddress.getByName(a).getAddress();   // a literal: no lookup
            return unmap(b);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** IPv4-mapped IPv6 ({@code ::ffff:a.b.c.d}) becomes plain IPv4. */
    private static byte[] unmap(byte[] b) {
        if (b.length == 16) {
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                mapped &= b[i] == 0;
            }
            mapped &= b[10] == (byte) 0xff && b[11] == (byte) 0xff;
            if (mapped) {
                return Arrays.copyOfRange(b, 12, 16);
            }
        }
        return b;
    }

    public boolean contains(String address) {
        byte[] a = literal(address);
        if (a == null || a.length != network.length) {
            return false;
        }
        int full = prefix / 8;
        for (int i = 0; i < full; i++) {
            if (a[i] != network[i]) {
                return false;
            }
        }
        int rest = prefix % 8;
        if (rest == 0) {
            return true;
        }
        int mask = (0xff << (8 - rest)) & 0xff;
        return (a[full] & mask) == (network[full] & mask);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Cidr c && c.prefix == prefix && Arrays.equals(c.network, network);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(network) * 31 + prefix;
    }

    @Override
    public String toString() {
        try {
            return InetAddress.getByAddress(network).getHostAddress() + "/" + prefix;
        } catch (UnknownHostException e) {
            return "?/" + prefix;
        }
    }
}
