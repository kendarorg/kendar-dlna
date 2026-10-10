package org.kendar.dlna.clients;

import java.util.List;

/**
 * When a profile applies: every non-empty part must hold. {@code userAgentRegex} is searched (substring) in the
 * User-Agent, case-insensitively; {@code ipCidrs} is any-of ({@code 192.168.1.0/24} or a single address).
 */
public record ProfileMatch(String userAgentRegex, List<HeaderMatch> headers, List<String> ipCidrs) {

    public ProfileMatch {
        headers = headers == null ? List.of() : List.copyOf(headers);
        ipCidrs = ipCidrs == null ? List.of() : List.copyOf(ipCidrs);
    }

    public static ProfileMatch userAgent(String regex) {
        return new ProfileMatch(regex, null, null);
    }

    boolean isEmpty() {
        return (userAgentRegex == null || userAgentRegex.isBlank()) && headers.isEmpty() && ipCidrs.isEmpty();
    }
}
