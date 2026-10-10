package org.kendar.dlna.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class StableIdsTest {

    @Test
    void idsAreStableUrlSafeAndKindSeparated() {
        RootRef ref = RootRef.parse("music/Rock/a.mp3");
        String id = StableIds.forPath("t", ref);
        assertThat(id).hasSize(16).matches("[A-Za-z0-9_-]+");
        assertThat(StableIds.forPath("t", ref)).isEqualTo(id);
        assertThat(StableIds.forPath("f", ref)).isNotEqualTo(id);
        assertThat(StableIds.forPath("t", RootRef.parse("music/Rock/b.mp3"))).isNotEqualTo(id);
    }

    @Test
    void rejectsBadKind() {
        assertThatThrownBy(() -> StableIds.id("Bad Kind", "x")).isInstanceOf(IllegalArgumentException.class);
    }
}
