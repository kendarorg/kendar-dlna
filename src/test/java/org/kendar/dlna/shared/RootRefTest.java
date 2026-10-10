package org.kendar.dlna.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RootRefTest {

    @Test
    void parsesAndFormatsRoundTrip() {
        RootRef r = RootRef.parse("music/Rock/Album 1/01 Intro.flac");
        assertThat(r.root()).isEqualTo("music");
        assertThat(r.rel()).isEqualTo("Rock/Album 1/01 Intro.flac");
        assertThat(r.qualified()).isEqualTo("music/Rock/Album 1/01 Intro.flac");
        assertThat(r.name()).isEqualTo("01 Intro.flac");
        assertThat(RootRef.parse(r.qualified())).isEqualTo(r);
    }

    @Test
    void rootAndParentChild() {
        RootRef root = RootRef.parse("music");
        assertThat(root.isRoot()).isTrue();
        assertThat(root.name()).isEqualTo("music");
        RootRef c = root.child("a").child("b.mp3");
        assertThat(c.parent().parent()).isEqualTo(root);
        assertThatThrownBy(root::parent).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void normalizesSeparatorsAndRejectsTraversal() {
        assertThat(new RootRef("m", "a\\b//c/").rel()).isEqualTo("a/b/c");
        assertThatThrownBy(() -> new RootRef("m", "a/../b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RootRef("m", "..")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RootRef("a/b", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RootRef(" ", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RootRef.parse("m").child("x/y")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonAsciiIsNfcNormalized() {
        String decomposed = "café";
        String composed = "café";
        assertThat(new RootRef("m", decomposed)).isEqualTo(new RootRef("m", composed));
    }
}
