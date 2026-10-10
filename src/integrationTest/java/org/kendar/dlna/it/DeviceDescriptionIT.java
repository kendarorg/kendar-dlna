package org.kendar.dlna.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.jupnp.model.meta.RemoteDevice;
import org.jupnp.model.meta.RemoteService;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/** Device description, service descriptions and the simple actions (capabilities, protocol info). */
@Order(10)
@TestMethodOrder(MethodOrderer.MethodName.class)
class DeviceDescriptionIT {

    private final Server server = Server.get();

    @Test
    void descriptionIsMediaServer1WithContentDirectoryAndConnectionManager() {
        Http.Response r = Http.get(server.descriptionUrl());
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.header("content-type")).hasValueSatisfying(v -> assertThat(v).containsIgnoringCase("xml"));
        String xml = r.text();
        assertThat(xml).contains("urn:schemas-upnp-org:device:MediaServer:1")
                .contains("urn:schemas-upnp-org:service:ContentDirectory:1")
                .contains("urn:schemas-upnp-org:service:ConnectionManager:1")
                .contains("<UDN>uuid:" + server.uuid() + "</UDN>");
    }

    @Test
    void jupnpParsesTheDeviceAndItsServices() {
        RemoteDevice d = server.upnp().device();
        assertThat(d.getType().getType()).isEqualTo("MediaServer");
        assertThat(d.getType().getVersion()).isEqualTo(1);
        assertThat(d.getDetails().getFriendlyName()).isNotBlank();
        assertThat(d.getIdentity().getUdn().getIdentifierString()).isEqualTo(server.uuid());
        RemoteService cd = server.upnp().contentDirectory();
        RemoteService cm = server.upnp().connectionManager();
        assertThat(cd).isNotNull();
        assertThat(cm).isNotNull();
        assertThat(cd.getServiceType().getVersion()).isEqualTo(1);
        assertThat(Arrays.stream(cd.getActions()).map(a -> a.getName()).toList())
                .contains("Browse", "Search", "GetSearchCapabilities", "GetSortCapabilities", "GetSystemUpdateID");
        assertThat(Arrays.stream(cm.getActions()).map(a -> a.getName()).toList())
                .contains("GetProtocolInfo", "GetCurrentConnectionIDs", "GetCurrentConnectionInfo");
        assertThat(cd.getEventSubscriptionURI()).isNotNull();
    }

    @Test
    void serviceDescriptorsAreServed() {
        RemoteService cd = server.upnp().contentDirectory();
        String scpd = server.rebase(cd.getDevice().normalizeURI(cd.getDescriptorURI()).toString());
        Http.Response r = Http.get(scpd);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text()).contains("<scpd").contains("Browse").contains("SystemUpdateID");
    }

    @Test
    void searchCapabilitiesListTheDocumentedProperties() {
        String caps = server.upnp().invoke(server.upnp().contentDirectory(), "GetSearchCapabilities", null)
                .get("SearchCaps");
        List<String> list = Arrays.stream(caps.split(",")).map(String::strip).toList();
        assertThat(list).contains("dc:title", "upnp:artist", "upnp:album", "upnp:genre", "dc:creator",
                "upnp:class");
    }

    @Test
    void sortCapabilitiesAreEmptyBecauseOrderComesFromSettings() {
        Map<String, String> out = server.upnp().invoke(server.upnp().contentDirectory(), "GetSortCapabilities",
                null);
        assertThat(out.get("SortCaps")).isIn("", "null");
    }

    @Test
    void protocolInfoListsAudioFormatsWithDlnaParameters() {
        Map<String, String> out = server.upnp().invoke(server.upnp().connectionManager(), "GetProtocolInfo", null);
        assertThat(out.get("Source")).contains("http-get:*:audio/mpeg:");
        assertThat(out.get("Sink")).isNotNull();
        for (String entry : out.get("Source").split(",")) {
            assertThat(entry.split(":")).as("protocolInfo %s", entry).hasSize(4);
        }
    }

    @Test
    void currentConnectionIdsAreAvailable() {
        Map<String, String> out = server.upnp().invoke(server.upnp().connectionManager(), "GetCurrentConnectionIDs",
                null);
        assertThat(out.get("ConnectionIDs")).contains("0");
    }

    @Test
    void systemUpdateIdIsReadable() {
        assertThat(server.upnp().systemUpdateId()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void healthEndpointIsUp() {
        Http.Response r = Http.get(server.baseUrl() + "/health");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text()).contains("UP");
    }
}
