package org.kendar.dlna.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class IgnoreRulesTest {

    @Test
    void nameWithoutSlashMatchesAtAnyDepth() {
        IgnoreRules r = new IgnoreRules(List.of("*.tmp"));
        assertThat(r.match("a.tmp", false)).isTrue();
        assertThat(r.match("x/y/a.tmp", false)).isTrue();
        assertThat(r.match("a.mp3", false)).isNull();
    }

    @Test
    void trailingSlashOnlyMatchesDirectories() {
        IgnoreRules r = new IgnoreRules(List.of("extras/"));
        assertThat(r.match("extras", true)).isTrue();
        assertThat(r.match("extras", false)).isNull();
    }

    @Test
    void slashAnchorsToTheDeclaringDirectory() {
        IgnoreRules r = new IgnoreRules(List.of("/Bonus"));
        assertThat(r.match("Bonus", true)).isTrue();
        assertThat(r.match("x/Bonus", true)).isNull();
    }

    @Test
    void lastMatchWinsAndNegationReincludes() {
        IgnoreRules r = new IgnoreRules(List.of("*.flac", "!keep.flac", "# comment", ""));
        assertThat(r.match("a.flac", false)).isTrue();
        assertThat(r.match("keep.flac", false)).isFalse();
    }

    @Test
    void doubleStarCrossesDirectories() {
        IgnoreRules r = new IgnoreRules(List.of("a/**/z.mp3"));
        assertThat(r.match("a/z.mp3", false)).isTrue();
        assertThat(r.match("a/b/c/z.mp3", false)).isTrue();
        assertThat(r.match("b/z.mp3", false)).isNull();
    }

    @Test
    void childInheritsSortAndKeepWholeButNotIdentity() {
        DirectorySettings parent = new DirectorySettings(SortMode.TITLE, List.of(), AlbumInfo.NONE, 5,
                List.of(ViewKind.ALBUM), 3, "Parent", true, 100);
        DirectorySettings child = DirectorySettings.DEFAULT.inheritFrom(parent);
        assertThat(child.sort()).isEqualTo(SortMode.TITLE);
        assertThat(child.keepWholeFiles()).isTrue();
        assertThat(child.bucketThreshold()).isEqualTo(100);
        assertThat(child.displayName()).isNull();
        assertThat(child.random()).isZero();
        assertThat(child.views()).isEmpty();
        DirectorySettings own = new DirectorySettings(SortMode.TRACK, List.of(), AlbumInfo.NONE, 0, List.of())
                .inheritFrom(parent);
        assertThat(own.sort()).isEqualTo(SortMode.TRACK);
    }
}
