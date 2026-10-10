package org.kendar.dlna.clients;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.kendar.dlna.shared.ClientId;

/** Picks the profile of a client: the first profile whose match holds, else the default. Immutable. */
public final class ClientProfileResolver {

    /** A profile with its patterns compiled once. */
    private record Compiled(ClientProfile profile, Pattern userAgent, List<Pattern> headerPatterns, List<Cidr> cidrs) {

        boolean matches(ClientRequest r) {
            ProfileMatch m = profile.match();
            if (userAgent != null && !userAgent.matcher(r.header("User-Agent").orElse("")).find()) {
                return false;
            }
            for (int i = 0; i < m.headers().size(); i++) {
                HeaderMatch h = m.headers().get(i);
                List<String> values = r.headerValues(h.name());
                Pattern p = headerPatterns.get(i);
                boolean ok = p == null ? !values.isEmpty() : values.stream().anyMatch(v -> p.matcher(v).find());
                if (ok == h.negate()) {
                    return false;
                }
            }
            if (!cidrs.isEmpty()) {
                InetAddress ip = Cidr.parseAddress(r.remoteIp());
                return ip != null && cidrs.stream().anyMatch(c -> c.contains(ip));
            }
            return true;
        }
    }

    private final List<Compiled> compiled;
    private final ClientProfile defaultProfile;

    /** @param ordered profiles in precedence order; they must have been validated (see {@link ClientProfiles}) */
    ClientProfileResolver(List<ClientProfile> ordered, ClientProfile defaultProfile) {
        List<Compiled> list = new ArrayList<>();
        for (ClientProfile p : ordered) {
            ProfileMatch m = p.match();
            Pattern ua = m.userAgentRegex() == null || m.userAgentRegex().isBlank() ? null
                    : Pattern.compile(m.userAgentRegex(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            List<Pattern> hp = new ArrayList<>();
            for (HeaderMatch h : m.headers()) {
                hp.add(h.compile());
            }
            List<Cidr> cidrs = m.ipCidrs().stream().map(Cidr::parse).toList();
            list.add(new Compiled(p, ua, hp, cidrs));
        }
        this.compiled = List.copyOf(list);
        this.defaultProfile = defaultProfile;
    }

    /** Never null: the default profile for a null or unknown request. */
    public ClientProfile resolve(ClientRequest request) {
        if (request != null) {
            for (Compiled c : compiled) {
                if (c.matches(request)) {
                    return c.profile();
                }
            }
        }
        return defaultProfile;
    }

    public ClientProfile resolve(ClientId id) {
        return id == null ? defaultProfile : resolve(ClientRequest.of(id.ip(), id.userAgent()));
    }

    /** Profiles in precedence order (the default excluded). */
    public List<ClientProfile> profiles() {
        return compiled.stream().map(Compiled::profile).toList();
    }

    public ClientProfile defaultProfile() {
        return defaultProfile;
    }

    /** {@code address[/prefix]} IPv4 or IPv6 literal; never triggers a DNS lookup. */
    static final class Cidr {
        private final byte[] network;
        private final int prefix;

        private Cidr(byte[] network, int prefix) {
            this.network = network;
            this.prefix = prefix;
        }

        static Cidr parse(String text) {
            String t = text.strip();
            int slash = t.indexOf('/');
            String addr = slash < 0 ? t : t.substring(0, slash);
            InetAddress ip = parseAddress(addr);
            if (ip == null) {
                throw new IllegalArgumentException("not an IP address: " + text);
            }
            byte[] b = ip.getAddress();
            int prefix = b.length * 8;
            if (slash >= 0) {
                try {
                    prefix = Integer.parseInt(t.substring(slash + 1).strip());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("invalid prefix length: " + text);
                }
                if (prefix < 0 || prefix > b.length * 8) {
                    throw new IllegalArgumentException("prefix length out of range: " + text);
                }
            }
            return new Cidr(b, prefix);
        }

        boolean contains(InetAddress ip) {
            byte[] b = ip.getAddress();
            if (b.length != network.length) {
                return false;
            }
            int full = prefix / 8;
            for (int i = 0; i < full; i++) {
                if (b[i] != network[i]) {
                    return false;
                }
            }
            int rest = prefix % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xff << (8 - rest)) & 0xff;
            return (b[full] & mask) == (network[full] & mask);
        }

        /** Parses an IPv4 dotted or IPv6 literal (IPv4-mapped IPv6 is unwrapped); null when not a literal. */
        static InetAddress parseAddress(String s) {
            if (s == null) {
                return null;
            }
            String t = s.strip();
            int pct = t.indexOf('%');
            if (pct >= 0) {
                t = t.substring(0, pct);   // scope id
            }
            boolean v4 = t.matches("\\d{1,3}(\\.\\d{1,3}){3}");
            boolean v6 = t.indexOf(':') >= 0 && t.matches("[0-9a-fA-F:.]+");
            if (!v4 && !v6) {
                return null;   // never resolve names
            }
            try {
                InetAddress a = InetAddress.getByName(t);   // a literal: no lookup happens
                if (a instanceof java.net.Inet6Address) {
                    byte[] raw = a.getAddress();
                    boolean mapped = true;
                    for (int i = 0; i < 10; i++) {
                        mapped &= raw[i] == 0;
                    }
                    if (mapped && raw[10] == (byte) 0xff && raw[11] == (byte) 0xff) {
                        return InetAddress.getByAddress(new byte[] {raw[12], raw[13], raw[14], raw[15]});
                    }
                }
                return a;
            } catch (UnknownHostException e) {
                return null;
            }
        }
    }
}
