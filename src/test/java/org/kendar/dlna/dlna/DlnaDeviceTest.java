package org.kendar.dlna.dlna;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.jupnp.model.meta.LocalDevice;
import org.jupnp.support.contentdirectory.ContentDirectoryException;
import org.jupnp.support.model.BrowseFlag;
import org.jupnp.support.model.BrowseResult;
import org.junit.jupiter.api.Test;
import org.kendar.dlna.catalog.SnapshotHolder;
import org.kendar.dlna.delivery.DefaultStreamUrlBuilder;

/** Pure unit tests: no Jetty, no network. */
class DlnaDeviceTest {

    private static BrowseEngine engine() {
        SnapshotHolder holder = new SnapshotHolder();
        return new BrowseEngine(holder, new DefaultStreamUrlBuilder(), () -> "http://127.0.0.1:1",
                new org.kendar.dlna.artwork.ArtworkService(holder, ref -> null, ref -> java.util.Optional.empty(),
                        java.nio.file.Path.of("unused")));
    }

    @Test
    void deviceHasMediaServerTypeFriendlyNameAndBothServices() throws Exception {
        UUID uuid = UUID.randomUUID();
        LocalDevice d = DlnaServer.createDevice(uuid, "Salotto", engine());

        assertThat(d.getType().getType()).isEqualTo("MediaServer");
        assertThat(d.getType().getVersion()).isEqualTo(1);
        assertThat(d.getDetails().getFriendlyName()).isEqualTo("Salotto");
        assertThat(d.getIdentity().getUdn().getIdentifierString()).isEqualTo(uuid.toString());
        assertThat(d.getServices()).extracting(s -> s.getServiceType().getType())
                .containsExactlyInAnyOrder("ContentDirectory", "ConnectionManager");
        assertThat(d.getServices()).allMatch(s -> s.getServiceType().getVersion() == 1);
    }

    @Test
    void serviceInstanceIsCreatedWithTheEngine() throws Exception {
        LocalDevice d = DlnaServer.createDevice(UUID.randomUUID(), "x", engine());
        Object cd = d.findService(new org.jupnp.model.types.UDAServiceType("ContentDirectory")).getManager()
                .getImplementation();
        assertThat(cd).isInstanceOf(LibraryContentDirectory.class);
    }

    @Test
    void emptyLibraryBrowsesWithoutFailing() throws Exception {
        LibraryContentDirectory cd = new LibraryContentDirectory(engine());

        BrowseResult folders = cd.browse("folders", BrowseFlag.DIRECT_CHILDREN, "*", 0, 0, null);

        assertThat(folders.getCountLong()).isZero();
        assertThat(folders.getTotalMatchesLong()).isZero();
        assertThat(cd.getSearchCapabilities().toString()).contains("dc:title");
    }

    @Test
    void unknownObjectIsRejected() {
        LibraryContentDirectory cd = new LibraryContentDirectory(engine());
        assertThatThrownBy(() -> cd.browse("nope", BrowseFlag.METADATA, "*", 0, 0, null))
                .isInstanceOf(ContentDirectoryException.class);
    }

    @Test
    void browseAndSearchAreBoundOnceWithTheStandardArguments() throws Exception {
        LocalDevice d = DlnaServer.createDevice(UUID.randomUUID(), "x", engine());
        org.jupnp.model.meta.LocalService<?> cd = d.findService(new org.jupnp.model.types.UDAServiceType("ContentDirectory"));
        assertThat(java.util.Arrays.stream(cd.getActions()).filter(a -> a.getName().equals("Search"))).hasSize(1);
        assertThat(java.util.Arrays.stream(cd.getActions()).filter(a -> a.getName().equals("Browse"))).hasSize(1);
        org.jupnp.model.meta.Action<?> search = cd.getAction("Search");
        assertThat(java.util.Arrays.stream(search.getInputArguments()).map(a -> a.getName()))
                .containsExactly("ContainerID", "SearchCriteria", "Filter", "StartingIndex", "RequestedCount",
                        "SortCriteria");
        assertThat(java.util.Arrays.stream(search.getOutputArguments()).map(a -> a.getName()))
                .containsExactly("Result", "NumberReturned", "TotalMatches", "UpdateID");
    }

    @Test
    void eventedSystemUpdateIdIsTheSnapshotVersion() throws Exception {
        SnapshotHolder holder = new SnapshotHolder(new org.kendar.dlna.catalog.Snapshot(12, java.time.Instant.EPOCH,
                java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.Map.of()));
        BrowseEngine e = new BrowseEngine(holder, new DefaultStreamUrlBuilder(), () -> "http://127.0.0.1:1",
                new org.kendar.dlna.artwork.ArtworkService(holder, ref -> null, ref -> java.util.Optional.empty(),
                        java.nio.file.Path.of("unused")));
        LocalDevice d = DlnaServer.createDevice(UUID.randomUUID(), "x", e);
        org.jupnp.model.meta.LocalService<?> cd = d.findService(new org.jupnp.model.types.UDAServiceType("ContentDirectory"));

        var state = cd.getManager().getCurrentState();

        assertThat(state).filteredOn(v -> v.getStateVariable().getName().equals("SystemUpdateID"))
                .singleElement().satisfies(v -> assertThat(v.getValue().toString()).isEqualTo("12"));
    }

    @Test
    void connectionManagerSourceListsNativeFormats() {
        assertThat(DlnaServer.sourceProtocols().toString()).contains("audio/mpeg", "audio/flac", "audio/ogg");
    }
}
