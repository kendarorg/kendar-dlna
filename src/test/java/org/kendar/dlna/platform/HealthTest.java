package org.kendar.dlna.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HealthTest {

    @Test
    void healthContextIsMountedAtHealthAndBodyIsJson() {
        assertThat(JettyHost.healthContext().getContextPath()).isEqualTo("/health");
        assertThat(JettyHost.HEALTH_BODY).isEqualTo("{\"status\":\"UP\"}");
    }
}
