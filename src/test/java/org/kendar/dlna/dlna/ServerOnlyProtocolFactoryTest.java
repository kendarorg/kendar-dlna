package org.kendar.dlna.dlna;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ServerOnlyProtocolFactoryTest {

    @Test
    void announcementsAndSearchResponsesOfOtherDevicesAreIgnored() {
        ServerOnlyProtocolFactory factory = new ServerOnlyProtocolFactory(null);

        assertThat(factory.createReceivingNotification(null)).isNull();
        assertThat(factory.createReceivingSearchResponse(null)).isNull();
    }
}
