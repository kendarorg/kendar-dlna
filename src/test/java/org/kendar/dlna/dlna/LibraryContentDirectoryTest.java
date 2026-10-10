package org.kendar.dlna.dlna;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.jupnp.model.types.UnsignedIntegerFourBytes;
import org.jupnp.support.model.BrowseFlag;
import org.jupnp.support.model.SortCriterion;
import org.junit.jupiter.api.Test;
import org.kendar.dlna.artwork.ArtworkService;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.SnapshotHolder;
import org.kendar.dlna.delivery.DefaultStreamUrlBuilder;

class LibraryContentDirectoryTest {

    private static LibraryContentDirectory service(long updateId) {
        SnapshotHolder holder = new SnapshotHolder(new Snapshot(updateId, Instant.EPOCH, List.of(), List.of(), List.of()));
        ArtworkService art = new ArtworkService(holder, ref -> null, ref -> Optional.empty(), Path.of("unused"));
        return new LibraryContentDirectory(new BrowseEngine(holder, new DefaultStreamUrlBuilder(), () -> "http://x", art));
    }

    @Test
    void searchCapabilitiesFollowThePlan() {
        assertThat(service(1).getSearchCapabilities().toString())
                .isEqualTo("dc:title,upnp:artist,upnp:album,upnp:genre,dc:creator,upnp:author,upnp:class");
    }

    @Test
    void sortCapabilitiesAreEmptyBecauseOrderComesFromSettings() {
        assertThat(service(1).getSortCapabilities().toString()).isEmpty();
    }

    @Test
    void systemUpdateIdFollowsTheSnapshot() {
        assertThat(service(9).getSystemUpdateID().getValue()).isEqualTo(9L);
    }

    @Test
    void browseIgnoresSortCriteriaAndUnknownFilter() throws Exception {
        var r = service(3).browse("0", BrowseFlag.DIRECT_CHILDREN, "", 0, 0,
                SortCriterion.valueOf("+dc:title,-upnp:artist"));
        assertThat(r.getTotalMatchesLong()).isEqualTo(2);
        assertThat(r.getContainerUpdateIDLong()).isEqualTo(3);
    }

    private static org.jupnp.model.profile.RemoteClientInfo remote(String ip, String userAgent) throws Exception {
        java.net.InetAddress addr = java.net.InetAddress.getByName(ip);
        org.jupnp.model.message.UpnpHeaders headers = new org.jupnp.model.message.UpnpHeaders();
        headers.add(org.jupnp.model.message.header.UpnpHeader.Type.USER_AGENT,
                new org.jupnp.model.message.header.UserAgentHeader(userAgent));
        return new org.jupnp.model.profile.RemoteClientInfo(new org.jupnp.model.message.Connection() {
            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public java.net.InetAddress getRemoteAddress() {
                return addr;
            }

            @Override
            public java.net.InetAddress getLocalAddress() {
                return addr;
            }
        }, headers);
    }

    @Test
    void clientIdentityComesFromTheRemoteClientInfo() throws Exception {
        assertThat(LibraryContentDirectory.clientOf(remote("10.1.2.3", "TestUA/1")))
                .isEqualTo(new org.kendar.dlna.shared.ClientId("10.1.2.3", "TestUA/1"));
        assertThat(LibraryContentDirectory.clientOf(null)).isEqualTo(org.kendar.dlna.shared.ClientId.UNKNOWN);
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void theBoundBrowseActionReceivesTheClientInfoFromTheInvocation() throws Exception {
        var lib = org.kendar.dlna.testutil.Fixtures.folder("lib",
                new org.kendar.dlna.catalog.DirectorySettings(null, null, null, 2, null));
        var tracks = List.of(org.kendar.dlna.testutil.Fixtures.track("lib/a.mp3"),
                org.kendar.dlna.testutil.Fixtures.track("lib/b.mp3"));
        SnapshotHolder holder = new SnapshotHolder(org.kendar.dlna.testutil.Fixtures.snapshot(List.of(lib), tracks));
        org.kendar.dlna.shuffle.Shuffle shuffle = new org.kendar.dlna.shuffle.Shuffle(holder::current);
        LibraryContentDirectory directory = new LibraryContentDirectory(new BrowseEngine(holder,
                new DefaultStreamUrlBuilder(), () -> "http://x",
                new ArtworkService(holder, ref -> null, ref -> Optional.empty(), Path.of("unused")), shuffle,
                new org.kendar.dlna.views.Views(holder::current)));

        org.jupnp.model.meta.LocalService<LibraryContentDirectory> ls =
                new ClientAwareServiceBinder().read(LibraryContentDirectory.class);
        ls.setManager(new org.jupnp.model.DefaultServiceManager<LibraryContentDirectory>(ls, null) {
            @Override
            protected LibraryContentDirectory createServiceInstance() {
                return directory;
            }
        });
        var action = ls.getAction("Browse");
        assertThat(action.getInputArguments()).extracting(a -> a.getName())
                .containsExactly("ObjectID", "BrowseFlag", "Filter", "StartingIndex", "RequestedCount",
                        "SortCriteria");

        for (String[] client : new String[][] { { "10.1.2.3", "UA-One" }, { "10.1.2.4", "UA-Two" } }) {
            var inv = new org.jupnp.model.action.RemoteActionInvocation(action, remote(client[0], client[1]));
            inv.setInput("ObjectID", "f:" + lib.id());
            inv.setInput("BrowseFlag", "BrowseDirectChildren");
            inv.setInput("Filter", "*");
            inv.setInput("StartingIndex", new UnsignedIntegerFourBytes(0));
            inv.setInput("RequestedCount", new UnsignedIntegerFourBytes(0));
            inv.setInput("SortCriteria", "");
            ls.getExecutor(action).execute(inv);
            assertThat(inv.getFailure()).isNull();
            assertThat(inv.getOutput("NumberReturned").getValue().toString()).isEqualTo("3");   // the Random container + 2 tracks
        }

        assertThat(shuffle.snapshots()).extracting(s -> s.client())
                .containsExactlyInAnyOrder(new org.kendar.dlna.shared.ClientId("10.1.2.3", "UA-One"),
                        new org.kendar.dlna.shared.ClientId("10.1.2.4", "UA-Two"));
    }
}
