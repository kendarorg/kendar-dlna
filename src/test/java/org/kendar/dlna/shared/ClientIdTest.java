package org.kendar.dlna.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClientIdTest {

    @Test
    void sameIpAndUserAgentAreEqual() {
        assertThat(new ClientId("10.0.0.2", " VLC/3 ")).isEqualTo(new ClientId("10.0.0.2", "VLC/3"));
        assertThat(new ClientId("10.0.0.2", "VLC")).isNotEqualTo(new ClientId("10.0.0.2", "Kodi"));
        assertThat(new ClientId("10.0.0.2", "VLC")).isNotEqualTo(new ClientId("10.0.0.3", "VLC"));
    }

    @Test
    void missingValuesBecomeUnknown() {
        assertThat(new ClientId(null, null)).isEqualTo(new ClientId("unknown", ""));
        assertThat(ClientId.UNKNOWN.ip()).isEqualTo("unknown");
    }
}
