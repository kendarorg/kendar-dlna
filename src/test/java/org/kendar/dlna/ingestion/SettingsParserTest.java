package org.kendar.dlna.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.DirectorySettings;
import org.kendar.dlna.catalog.SortMode;
import org.kendar.dlna.catalog.ViewKind;

class SettingsParserTest {

    @TempDir
    Path dir;

    @Test
    void parsesAllFields() {
        SettingsParser.Result r = SettingsParser.parse("""
                {"sort":"explicit","order":["b.mp3","sub"],
                 "album":{"title":"T","albumArtist":"AA","year":1999,"genre":"Rock","discNumber":"2","discTotal":3},
                 "random":25,"recent":40,"views":["album","genre","album","composer"]}""");

        assertThat(r.error()).isNull();
        DirectorySettings s = r.settings();
        assertThat(s.sort()).isEqualTo(SortMode.EXPLICIT);
        assertThat(s.order()).containsExactly("b.mp3", "sub");
        assertThat(s.album().title()).isEqualTo("T");
        assertThat(s.album().albumArtist()).isEqualTo("AA");
        assertThat(s.album().year()).isEqualTo(1999);
        assertThat(s.album().discNumber()).isEqualTo(2);
        assertThat(s.album().discTotal()).isEqualTo(3);
        assertThat(s.random()).isEqualTo(25);
        assertThat(s.recent()).isEqualTo(40);
        assertThat(s.views()).containsExactly(ViewKind.ALBUM, ViewKind.GENRE, ViewKind.COMPOSER);
    }

    @Test
    void parsesDisplayNameTrimmedAndBlankIsUnset() {
        assertThat(SettingsParser.parse("{\"displayName\":\"  My Dir \"}").settings().displayName()).isEqualTo("My Dir");
        assertThat(SettingsParser.parse("{\"displayName\":\"  \"}").settings().displayName()).isNull();
        assertThat(SettingsParser.parse("{\"displayName\":null}").settings().displayName()).isNull();
        assertThat(SettingsParser.parse("{\"displayName\":null}").error()).isNull();
        for (String bad : new String[] { "{\"displayName\":5}", "{\"displayName\":[\"a\"]}", "{\"displayName\":true}" }) {
            SettingsParser.Result r = SettingsParser.parse(bad);
            assertThat(r.error()).as(bad).contains("displayName");
            assertThat(r.settings()).as(bad).isEqualTo(DirectorySettings.DEFAULT);
        }
    }

    @Test
    void parsesKeepWholeFiles() {
        assertThat(SettingsParser.parse("{}").settings().keepWholeFiles()).isFalse();
        assertThat(SettingsParser.parse("{\"keepWholeFiles\":true}").settings().keepWholeFiles()).isTrue();
        assertThat(SettingsParser.parse("{\"keepWholeFiles\":null}").error()).isNull();
        SettingsParser.Result bad = SettingsParser.parse("{\"keepWholeFiles\":\"yes\"}");
        assertThat(bad.error()).contains("keepWholeFiles");
        assertThat(bad.settings()).isEqualTo(DirectorySettings.DEFAULT);
    }

    @Test
    void parsesBucketThreshold() {
        assertThat(SettingsParser.parse("{}").settings().bucketThreshold()).isNull();
        assertThat(SettingsParser.parse("{\"bucketThreshold\":null}").settings().bucketThreshold()).isNull();
        assertThat(SettingsParser.parse("{\"bucketThreshold\":50}").settings().bucketThreshold()).isEqualTo(50);
        assertThat(SettingsParser.parse("{\"bucketThreshold\":0}").error()).contains("bucketThreshold");
        assertThat(SettingsParser.parse("{\"bucketThreshold\":\"50\"}").error()).contains("bucketThreshold");
    }

    @Test
    void emptyObjectAndEmptyFileGiveDefaults() {
        assertThat(SettingsParser.parse("{}")).isEqualTo(new SettingsParser.Result(DirectorySettings.DEFAULT, null));
        assertThat(SettingsParser.parse("   ").error()).isNull();
        assertThat(SettingsParser.parse("{\"unknown\":1}").error()).isNull();
    }

    @Test
    void malformedInputsGiveDefaultsAndAnError() {
        for (String bad : new String[] { "{not json", "[1,2]", "{\"sort\":\"random\"}", "{\"sort\":5}",
                "{\"order\":\"x\"}", "{\"order\":[1]}", "{\"album\":3}", "{\"album\":{\"year\":\"abc\"}}",
                "{\"random\":-1}", "{\"recent\":-1}", "{\"recent\":\"5\"}", "{\"random\":\"5\"}", "{\"views\":[\"nope\"]}", "{\"views\":\"album\"}" }) {
            SettingsParser.Result r = SettingsParser.parse(bad);
            assertThat(r.error()).as(bad).isNotNull();
            assertThat(r.settings()).as(bad).isEqualTo(DirectorySettings.DEFAULT);
        }
    }

    @Test
    void parseFileHandlesUnreadableFile() throws Exception {
        Path f = dir.resolve("settings.json");
        Files.writeString(f, "{\"sort\":\"title\"}");
        assertThat(SettingsParser.parseFile(f).settings().sort()).isEqualTo(SortMode.TITLE);
        assertThat(SettingsParser.parseFile(dir.resolve("missing.json")).error()).contains("cannot read");
    }
}
