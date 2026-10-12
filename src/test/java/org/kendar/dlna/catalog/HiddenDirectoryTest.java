package org.kendar.dlna.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.kendar.dlna.testutil.Fixtures.folder;
import static org.kendar.dlna.testutil.Fixtures.snapshot;
import static org.kendar.dlna.testutil.Fixtures.track;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.kendar.dlna.ingestion.SettingsParser;
import org.kendar.dlna.shared.RootRef;

class HiddenDirectoryTest {

    @Test
    void aHiddenDirectoryAndItsSubtreeLeaveEveryListingButStayForTheAdmin() {
        DirectorySettings hidden = SettingsParser.parse("{\"hidden\": true}").settings();
        assertThat(hidden.hidden()).isTrue();
        Snapshot s = snapshot(List.of(folder("m"), folder("m/shown"), folder("m/secret", hidden), folder("m/secret/deep")),
                List.of(track("m/shown/a.mp3"), track("m/secret/b.mp3"), track("m/secret/deep/c.mp3")));
        RootRef root = s.roots().get(0).ref();
        assertThat(s.children(root)).hasSize(1);
        assertThat(s.allChildren(root)).hasSize(2);
        assertThat(s.subtreeTracks(root)).extracting(t -> t.ref().rel()).containsExactly("shown/a.mp3");
        assertThat(s.visibleTracks()).extracting(t -> t.ref().rel()).containsExactly("shown/a.mp3");
        assertThat(s.folder(new RootRef("m", "secret"))).isPresent();
    }

    @Test
    void hiddenMustBeABoolean() {
        assertThat(SettingsParser.parse("{\"hidden\": \"yes\"}").error()).contains("hidden");
    }
}
