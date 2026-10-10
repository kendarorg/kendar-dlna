package org.kendar.dlna.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.jupnp.UpnpServiceImpl;
import org.jupnp.model.message.header.UDADeviceTypeHeader;
import org.jupnp.model.meta.RemoteDevice;
import org.jupnp.model.types.UDADeviceType;
import org.jupnp.registry.DefaultRegistryListener;
import org.jupnp.registry.Registry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.kendar.dlna.dlna.DlnaConfiguration;
import org.kendar.dlna.platform.JettyHost;

/**
 * OPTIONAL: SSDP multicast discovery of a server on the LAN. Multicast does not cross the Docker bridge, so this
 * never runs against the Testcontainers container; enable it with {@code -Dkendar.it.ssdp=true} (or
 * {@code KENDAR_IT_SSDP=true}) when a server (typically a host-networking deployment, see
 * {@code KENDAR_IT_BASE_URL}) is on this machine's network. Not enabled: skipped.
 */
@Order(110)
class DiscoveryIT {

    @Test
    void serverAnswersAnSsdpMulticastSearchAndItsAdvertisedUrlsWork() throws Exception {
        Assumptions.assumeTrue(ItEnv.ssdp(),
                "SSDP discovery test disabled: set -Dkendar.it.ssdp=true / KENDAR_IT_SSDP=true with a server on the LAN");
        String expectedHost = ItEnv.baseUrl() == null ? null : URI.create(ItEnv.baseUrl()).getHost();

        JettyHost callback = new JettyHost(0);
        callback.start();
        UpnpServiceImpl upnp = new UpnpServiceImpl(new DlnaConfiguration(callback.contexts(), callback.port()));
        CompletableFuture<RemoteDevice> found = new CompletableFuture<>();
        try {
            upnp.startup();
            upnp.getRegistry().addListener(new DefaultRegistryListener() {
                @Override
                public void remoteDeviceAdded(Registry registry, RemoteDevice device) {
                    boolean ours = device.getDetails() != null && device.getDetails().getManufacturerDetails() != null
                            && "Kendar".equals(device.getDetails().getManufacturerDetails().getManufacturer());
                    boolean host = expectedHost == null
                            || expectedHost.equals(device.getIdentity().getDescriptorURL().getHost());
                    if (ours && host) {
                        found.complete(device);
                    }
                }
            });
            RemoteDevice device = null;
            for (int attempt = 0; attempt < 10 && device == null; attempt++) {
                upnp.getControlPoint().search(new UDADeviceTypeHeader(new UDADeviceType("MediaServer", 1)));
                try {
                    device = found.get(4, TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    // search again: UDP is lossy
                }
            }
            assertThat(device).as("a Kendar MediaServer:1 answering the M-SEARCH"
                    + (expectedHost == null ? "" : " from " + expectedHost)).isNotNull();

            // browse it through the address it advertises, and stream from the URL it advertises (no rebasing)
            try (UpnpClient client = new UpnpClient(device.getIdentity().getDescriptorURL().toString(),
                    device.getIdentity().getUdn().getIdentifierString())) {
                assertThat(client.children("0", 0, 0).total()).isGreaterThanOrEqualTo(2);
                var first = client.search("0", "upnp:class derivedfrom \"object.item.audioItem\"", 0, 1);
                if (first.total() > 0) {
                    String url = first.didl().getItems().get(0).getResources().get(0).getValue();
                    assertThat(Http.head(url).status()).as("advertised stream URL %s", url).isEqualTo(200);
                }
            }
        } finally {
            upnp.shutdown();
            callback.stop();
        }
    }
}
