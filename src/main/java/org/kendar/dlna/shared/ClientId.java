package org.kendar.dlna.shared;

/**
 * Identity of a DLNA client: remote IP plus User-Agent (decision D10). Used to scope per-client state such as
 * Random N snapshots.
 */
public record ClientId(String ip, String userAgent) {

    public static final ClientId UNKNOWN = new ClientId("unknown", "");

    public ClientId {
        ip = ip == null || ip.isBlank() ? "unknown" : ip.strip();
        userAgent = userAgent == null ? "" : userAgent.strip();
    }
}
