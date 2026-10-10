package org.kendar.dlna.dlna;

import static org.assertj.core.api.Assertions.assertThat;
import static org.kendar.dlna.testutil.Fixtures.track;

import org.junit.jupiter.api.Test;
import org.kendar.dlna.catalog.Track;

class ObjectIdsTest {

    @Test
    void containerAndContextItemIdsRoundTrip() {
        Track t = track("m/a.mp3");
        String ctx = "r.dirId-1.abc123";

        assertThat(ObjectIds.containerContext(ObjectIds.container(ctx))).contains(ctx);
        ObjectIds.ContextItem item = ObjectIds.contextItem(ObjectIds.contextItem(ctx, t)).orElseThrow();
        assertThat(item.context()).isEqualTo(ctx);
        assertThat(item.trackId()).isEqualTo(t.id());
    }

    @Test
    void malformedIdsAreRejected() {
        assertThat(ObjectIds.containerContext("c:")).isEmpty();
        assertThat(ObjectIds.containerContext("x:y")).isEmpty();
        assertThat(ObjectIds.contextItem("i:noseparator")).isEmpty();
        assertThat(ObjectIds.contextItem("i:ctx:")).isEmpty();
        assertThat(ObjectIds.contextItem(null)).isEmpty();
    }
}
