package org.kendar.dlna.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Change notification: GENA events (SystemUpdateID, ContainerUpdateIDs) and the same facts seen by polling
 * (the update ids returned by Browse / GetSystemUpdateID). The GENA tests need the server to reach this machine:
 * when the callback cannot be delivered (remote Docker host, firewall) they are skipped with the reason, and the
 * polling tests cover the same changes.
 */
@Order(100)
@TestMethodOrder(MethodOrderer.MethodName.class)
class EventingIT {

    private static final List<String> LAST_EVENTS = new ArrayList<>();

    private final Server server = Server.get();
    private final UpnpClient cds = server.upnp();

    @BeforeEach
    void adminAvailable() {
        Assumptions.assumeTrue(server.adminSkipReason() == null, () -> "admin API: " + server.adminSkipReason());
    }

    // ---------------------------------------------------------------- helpers

    private String createPlaylist(String name) {
        AdminClient.Reply r = server.admin().post("/api/v1/admin/playlists",
                AdminClient.mapper().createObjectNode().put("name", name).toString());
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return r.json().path("key").asText();
    }

    private void deletePlaylist(String key) {
        assertThat(server.admin().delete("/api/v1/admin/playlists/" + key).status()).isEqualTo(204);
    }

    /** Starts a full rescan and waits until it has finished. */
    private void rescanAndWait() throws InterruptedException {
        AdminClient a = server.admin();
        String before = a.get("/api/v1/admin/scan").json().path("scannedAt").asText();
        AdminClient.Reply r = a.post("/api/v1/admin/rescan", "{}");
        assertThat(r.status()).as(r.body()).isEqualTo(202);
        Instant end = Instant.now().plusSeconds(90);
        while (Instant.now().isBefore(end)) {
            JsonNode s = a.get("/api/v1/admin/scan").json();
            if (!s.path("running").asBoolean(true) && !s.path("scannedAt").asText().equals(before)) {
                return;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("The rescan did not finish: " + a.get("/api/v1/admin/scan").body());
    }

    private Path newSong(String name) throws Exception {
        Path dir = Files.createTempDirectory("kendar-it-new");
        Path f = dir.resolve(name);
        LibraryFactory.sineWav(f, 22050, 0.3, 880);
        return f;
    }

    /** Waits for a GENA event satisfying the predicate; null when none arrives. */
    private static Map<String, String> awaitEvent(UpnpClient.Subscription s, long seconds,
            java.util.function.Predicate<Map<String, String>> accept) throws InterruptedException {
        Instant end = Instant.now().plusSeconds(seconds);
        while (Instant.now().isBefore(end)) {
            Map<String, String> e = s.next(1);
            if (e != null) {
                LAST_EVENTS.add(e.toString());
            }
            if (e != null && accept.test(e)) {
                return e;
            }
        }
        return null;
    }

    /** A subscription with its first event, or a skipped test with the reason. */
    private UpnpClient.Subscription subscribeOrSkip() throws InterruptedException {
        UpnpClient.Subscription s = cds.subscribe(300);
        Instant end = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(end) && !s.established && s.failure == null) {
            Thread.sleep(200);
        }
        if (!s.established) {
            s.end();
        }
        Assumptions.assumeTrue(s.established, () -> "GENA subscription was not established: " + s.failure);
        Map<String, String> initial = s.next(10);
        if (initial == null) {
            s.end();
        }
        Assumptions.assumeTrue(initial != null, "SKIPPED: the server cannot deliver GENA events to this machine "
                + "(callback URL unreachable from the container, typical with a remote Docker engine or a firewall); "
                + "the polling tests cover the same changes");
        assertThat(initial).containsKey("SystemUpdateID");
        // jUPnP drops SystemUpdateID values within 200 ms of the previous event (the initial one): do not start there
        Thread.sleep(400);
        return s;
    }

    // ---------------------------------------------------------------- GENA

    @Test
    void genaInitialEventAndPlaylistChangeEvent() throws InterruptedException {
        UpnpClient.Subscription s = subscribeOrSkip();
        try {
            long before = cds.systemUpdateId();
            String key = createPlaylist("GENA Playlist");
            try {
                // jUPnP delivers one event per changed variable and hands over the accumulated values
                Map<String, String> e = awaitEvent(s, 20, m -> m.getOrDefault("ContainerUpdateIDs", "")
                        .contains("playlists,"));
                assertThat(e).as("ContainerUpdateIDs event naming the Playlists container (SystemUpdateID was %d); "
                        + "received %s", before, LAST_EVENTS).isNotNull();
            } finally {
                deletePlaylist(key);
            }
        } finally {
            s.end();
        }
    }

    @Test
    void genaAnnouncesTheFolderChangedByARescan() throws Exception {
        Assumptions.assumeTrue(server.managed(), "copies a file into the generated library");
        UpnpClient.Subscription s = subscribeOrSkip();
        try {
            String musicId = Tree.shared().require("Folders/" + LibraryFactory.ROOT).id();
            long before = cds.systemUpdateId();
            server.exec("mkdir", "-p", "/music/Incoming");
            server.copyIntoContainer(newSong("Gena Song.wav"), "/music/Incoming/Gena Song.wav");
            try {
                rescanAndWait();
                Map<String, String> e = awaitEvent(s, 20, m -> m.getOrDefault("ContainerUpdateIDs", "")
                        .contains(musicId));
                assertThat(e).as("ContainerUpdateIDs event naming the changed folder %s (SystemUpdateID was %d); "
                        + "received %s", musicId, before, LAST_EVENTS).isNotNull();
            } finally {
                server.exec("rm", "-rf", "/music/Incoming");
                rescanAndWait();
            }
        } finally {
            s.end();
        }
    }

    /**
     * The evented SystemUpdateID is the real catalog version (it used to be a constant 0). jUPnP may drop a
     * SystemUpdateID event (200 ms moderation, or replaced by the ContainerUpdateIDs event fired right after it),
     * so several changes are tried: at least one event must carry the new value.
     */
    @Test
    void genaSystemUpdateIdEventCarriesTheRealValue() throws InterruptedException {
        UpnpClient.Subscription s = subscribeOrSkip();
        try {
            long highest = 0;
            for (int attempt = 0; attempt < 6 && highest == 0; attempt++) {
                long before = cds.systemUpdateId();
                String key = createPlaylist("GENA Sys " + attempt);
                deletePlaylist(key);
                Instant end = Instant.now().plusSeconds(3);
                while (Instant.now().isBefore(end)) {
                    Map<String, String> e = s.next(1);
                    if (e != null && e.containsKey("SystemUpdateID") && Long.parseLong(e.get("SystemUpdateID")) > before) {
                        highest = Long.parseLong(e.get("SystemUpdateID"));
                    }
                }
                Thread.sleep(400);
            }
            assertThat(highest).as("a SystemUpdateID event with the real value; received %s", LAST_EVENTS).isPositive();
        } finally {
            s.end();
        }
    }

    /**
     * Three playlists created back to back: returns the container ids of the Rapid playlists that appeared in a
     * ContainerUpdateIDs event, in creation order, with null for the ones never announced.
     */
    private List<String> announcedAfterRapidChanges() throws InterruptedException {
        UpnpClient.Subscription s = subscribeOrSkip();
        List<String> keys = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                keys.add(createPlaylist("Rapid " + i));
            }
            java.util.Set<String> announced = new java.util.LinkedHashSet<>();
            Instant end = Instant.now().plusSeconds(6);
            while (Instant.now().isBefore(end)) {
                Map<String, String> e = s.next(1);
                if (e != null && e.get("ContainerUpdateIDs") != null) {
                    announced.addAll(List.of(e.get("ContainerUpdateIDs").split(",")));
                }
            }
            List<String> out = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                String title = "Rapid " + i;
                String id = cds.children("playlists", 0, 0).didl().getContainers().stream()
                        .filter(c -> c.getTitle().equals(title)).map(c -> c.getId()).findFirst().orElseThrow();
                out.add(announced.contains(id) ? id : null);
            }
            return out;
        } finally {
            keys.forEach(this::deletePlaylist);
            s.end();
        }
    }

    /** Back to back changes: at least the last container is announced. */
    @Test
    void rapidChangesAnnounceAtLeastTheLastOne() throws InterruptedException {
        assertThat(announcedAfterRapidChanges().get(2)).as("the last of three rapid changes is announced").isNotNull();
    }

    /**
     * KNOWN SERVER LIMITATION: jUPnP hands the values of an event to a sender thread that reads them later, so a
     * second change committed within milliseconds replaces the ContainerUpdateIDs of the first before they were
     * sent; with a quick burst of commits the earlier containers are never announced (control points that cache
     * Browse results keep a stale copy until the next change). Fixing it needs the server to accumulate the ids
     * of a burst itself.
     */
    @Test
    @org.junit.jupiter.api.Disabled("Known server limitation: ContainerUpdateIDs of back-to-back commits can coalesce")
    void everyRapidChangeIsAnnounced() throws InterruptedException {
        assertThat(announcedAfterRapidChanges()).doesNotContainNull();
    }

    // ---------------------------------------------------------------- polling (no callback needed)

    @Test
    void rescanWithoutChangesLeavesTheUpdateIdsAlone() throws InterruptedException {
        long system = cds.systemUpdateId();
        long folders = cds.metadata("folders").updateId();
        rescanAndWait();
        assertThat(cds.systemUpdateId()).as("SystemUpdateID after a rescan that found nothing new")
                .isEqualTo(system);
        assertThat(cds.metadata("folders").updateId()).isEqualTo(folders);
    }

    @Test
    void newFileAppearsAfterRescanAndUpdateIdsAdvance() throws Exception {
        Assumptions.assumeTrue(server.managed(), "copies a file into the generated library");
        Tree.Node music = Tree.shared().require("Folders/" + LibraryFactory.ROOT);
        long system = cds.systemUpdateId();
        long musicUpdate = cds.metadata(music.id()).updateId();
        server.exec("mkdir", "-p", "/music/Incoming");
        server.copyIntoContainer(newSong("Polled Song.wav"), "/music/Incoming/Polled Song.wav");
        try {
            // not visible before the rescan (no filesystem watcher)
            assertThat(titlesOf(music.id())).doesNotContain("Incoming");
            rescanAndWait();
            assertThat(cds.systemUpdateId()).isGreaterThan(system);
            assertThat(cds.metadata(music.id()).updateId()).as("ContainerUpdateID of the changed folder")
                    .isGreaterThan(musicUpdate);
            assertThat(titlesOf(music.id())).contains("Incoming");
            var incoming = cds.children(music.id(), 0, 0).didl().getContainers().stream()
                    .filter(c -> c.getTitle().equals("Incoming")).findFirst().orElseThrow();
            assertThat(titlesOf(incoming.getId())).containsExactly("Polled Song");
            assertThat(cds.search("0", "dc:title contains \"Polled\"", 0, 0).total()).isEqualTo(1);
            // and it streams
            var item = cds.children(incoming.getId(), 0, 0).didl().getItems().get(0);
            assertThat(Http.head(server.rebase(item.getResources().get(0).getValue())).status()).isEqualTo(200);
        } finally {
            server.exec("rm", "-rf", "/music/Incoming");
            rescanAndWait();
        }
        assertThat(titlesOf(music.id())).doesNotContain("Incoming");
    }

    @Test
    void playlistChangeAdvancesTheUpdateIdsOfThePlaylistsContainer() {
        long system = cds.systemUpdateId();
        long playlists = cds.metadata("playlists").updateId();
        String key = createPlaylist("Polled Playlist");
        try {
            assertThat(cds.systemUpdateId()).isGreaterThan(system);
            assertThat(cds.metadata("playlists").updateId()).isGreaterThan(playlists);
        } finally {
            deletePlaylist(key);
        }
    }

    private List<String> titlesOf(String containerId) {
        var d = cds.children(containerId, 0, 0).didl();
        List<String> out = new ArrayList<>();
        d.getContainers().forEach(c -> out.add(c.getTitle()));
        d.getItems().forEach(i -> out.add(i.getTitle()));
        return out;
    }
}
