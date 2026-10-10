package org.kendar.dlna.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void ignoresUnknownPropertiesAndRoundTrips() throws Exception {
        record R(String a) {
        }
        R r = Json.mapper().readValue("{\"a\":\"x\",\"zzz\":1}", R.class);
        assertThat(r.a()).isEqualTo("x");
        assertThat(Json.mapper().readValue(Json.mapper().writeValueAsString(Map.of("k", 1)), Map.class))
                .containsEntry("k", 1);
    }
}
