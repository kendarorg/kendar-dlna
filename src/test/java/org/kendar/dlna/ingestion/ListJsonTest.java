package org.kendar.dlna.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.TrackTags;

class ListJsonTest {

    private static final Path MIDI = Path.of("dev", "music", "midi");

    private static ListJson.Result parse(String s) {
        return ListJson.parse(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parsesTheDocumentedExample() {
        ListJson.Result r = parse("""
                [
                    {"filename":"franpani.mid","author":"FRANCK, César (1822-90)","partOf":"",
                     "title":"Panis Angelicus","transcriber":"F.Raborn"},
                    {"filename":"franck0.mid","author":"FRANCK, César (1822-90)","partOf":"Organ",
                     "title":"Chorale No.1 in E Major for Organ","transcriber":"A.Gunther"}
                ]""");
        assertThat(r.error()).isNull();
        assertThat(r.entries()).hasSize(2);
        ListJson.Entry e = r.entries().get(0);
        assertThat(e.filename()).isEqualTo("franpani.mid");
        assertThat(e.author()).isEqualTo("FRANCK, César (1822-90)");
        assertThat(e.partOf()).isNull();   // empty = not given
        assertThat(e.title()).isEqualTo("Panis Angelicus");
        assertThat(e.transcriber()).isEqualTo("F.Raborn");
        assertThat(r.hash()).isNotNull();
    }

    @Test
    void skipsBomsAndReadsLegacyEncodings() {
        String text = "[{\"filename\":\"a.mid\",\"title\":\"Albéniz\"}]";
        byte[] json = text.getBytes(StandardCharsets.UTF_8);
        byte[] bom = new byte[json.length + 3];
        bom[0] = (byte) 0xEF;
        bom[1] = (byte) 0xBB;
        bom[2] = (byte) 0xBF;
        System.arraycopy(json, 0, bom, 3, json.length);
        assertThat(ListJson.parse(bom).entries().get(0).title()).isEqualTo("Albéniz");

        byte[] utf16 = text.getBytes(StandardCharsets.UTF_16LE);
        byte[] withBom = new byte[utf16.length + 2];
        withBom[0] = (byte) 0xFF;
        withBom[1] = (byte) 0xFE;
        System.arraycopy(utf16, 0, withBom, 2, utf16.length);
        assertThat(ListJson.parse(withBom).entries().get(0).title()).isEqualTo("Albéniz");

        ListJson.Result r = ListJson.parse(text.getBytes(Charset.forName("windows-1252")));
        assertThat(r.error()).isNull();
        assertThat(r.entries().get(0).title()).isEqualTo("Albéniz");
    }

    @Test
    void toleratesSloppyJson() {
        ListJson.Result r = parse("""
                // hand written
                [
                  {'filename': 'a.mid', 'title': 'One', 'transcriber': 12,},
                  {"filename": "B.MID", "Title": "Two", /* c */ "author": null},
                ]""");
        assertThat(r.error()).isNull();
        assertThat(r.entries()).extracting(ListJson.Entry::title).containsExactly("One", "Two");
        assertThat(r.entries().get(0).transcriber()).isEqualTo("12");
        assertThat(r.entries().get(1).author()).isNull();
    }

    @Test
    void badEntriesAreSkippedWithWarningsAndDuplicatesKeepTheFirst() {
        ListJson.Result r = parse("""
                [ 5, {"title":"no name"}, {"filename":"  "}, {"filename":"dir\\\\sub/x.mid","title":"X"},
                  {"filename":"y.mid","title":"first"}, {"filename":"Y.MID","title":"second"} ]""");
        assertThat(r.error()).isNull();
        assertThat(r.entries()).extracting(ListJson.Entry::filename).containsExactly("x.mid", "y.mid");
        assertThat(r.find("Y.mid").orElseThrow().title()).isEqualTo("first");
        assertThat(r.warnings()).hasSize(4);
    }

    @Test
    void anUnusableFileIsAnErrorNotAnException() {
        assertThat(parse("[{\"filename\":").error()).startsWith("malformed JSON");
        assertThat(parse("\"text\"").error()).contains("array");
        assertThat(parse("{\"a\":1}").error()).contains("array");
        assertThat(parse("").error()).isNull();
        assertThat(parse("   ").entries()).isEmpty();
        assertThat(parse("null").entries()).isEmpty();
        assertThat(parse("{\"filename\":\"one.mid\",\"title\":\"T\"}").entries()).hasSize(1);
        assertThat(parse("{\"tracks\":[{\"filename\":\"one.mid\"}]}").entries()).hasSize(1);
        // the hash identifies the content
        assertThat(parse("[]").hash()).isNotEqualTo(parse("[ ]").hash()).isEqualTo(parse("[]").hash());
    }

    @Test
    void matchesFileNamesCaseInsensitivelyAndUnicodeNormalized() {
        ListJson.Result r = parse("[{\"filename\":\"Albéniz.MID\",\"title\":\"T\"}]");   // e + combining acute
        assertThat(r.find("ALBÉNIZ.mid")).isPresent();
        assertThat(r.find("other.mid")).isEmpty();
    }

    @Test
    void locatesTheFileInAnyLetterCaseAndMissingMeansNone(@TempDir Path dir) throws IOException {
        assertThat(ListJson.forDirectory(dir)).isEqualTo(ListJson.Result.NONE);
        Files.writeString(dir.resolve("List.JSON"), "[{\"filename\":\"a.mid\",\"title\":\"T\"}]");
        assertThat(ListJson.forDirectory(dir).entries()).hasSize(1);
        assertThat(ListJson.forDirectory(dir.resolve("nope")).exists()).isFalse();
    }

    // ---- merge precedence ----

    @Test
    void listValuesOverrideEmbeddedTagsFieldByField() {
        TrackTags tags = new TrackTags("Tag title", "Tag artist", "Tag album", "AA", "Classical", 1900, 3, 1, 2,
                List.of("A1", "A2"), null, null, "Composer", null, null);
        TrackTags merged = ListJson.apply(tags, new ListJson.Entry("x.mid", "Author", null, "List title", "Raborn"));
        assertThat(merged.title()).isEqualTo("List title");
        assertThat(merged.artist()).isEqualTo("Author");
        assertThat(merged.allArtists()).containsExactly("Author");   // the multi-values are replaced too
        assertThat(merged.album()).isEqualTo("Tag album");           // not given: the tag stays
        assertThat(merged.transcriber()).isEqualTo("Raborn");
        assertThat(merged.composer()).isEqualTo("Composer");
        assertThat(merged.year()).isEqualTo(1900);
        assertThat(merged.trackNo()).isEqualTo(3);

        assertThat(ListJson.apply(tags, null)).isSameAs(tags);
        assertThat(ListJson.apply(tags, new ListJson.Entry("x.mid", null, null, null, null))).isSameAs(tags);
        assertThat(ListJson.apply(TrackTags.EMPTY, new ListJson.Entry("x.mid", null, "Set", null, null)).album())
                .isEqualTo("Set");
    }

    // ---- the real samples ----

    @Test
    void parsesEverySampleListJsonOfDevMusic() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(MIDI), "dev/music/midi is not available");
        int files = 0;
        int entries = 0;
        try (Stream<Path> walk = Files.walk(MIDI)) {
            for (Path p : walk.filter(x -> x.getFileName().toString().equals("list.json")).toList()) {
                ListJson.Result r = ListJson.parseFile(p);
                assertThat(r.error()).as(p.toString()).isNull();
                assertThat(r.entries()).as(p.toString()).isNotEmpty();
                files++;
                entries += r.entries().size();
                for (ListJson.Entry e : r.entries()) {
                    assertThat(Files.exists(p.resolveSibling(e.filename()))).as(p + " " + e.filename()).isTrue();
                }
            }
        }
        assertThat(files).isGreaterThan(400);
        assertThat(entries).isGreaterThan(2000);
    }

    @Test
    void theMozartSampleHasADuplicateEntryWhoseFirstOneWins() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(MIDI), "dev/music/midi is not available");
        try (Stream<Path> walk = Files.walk(MIDI)) {
            Path p = walk.filter(
                    x -> x.toString().replace('\\', '/').contains("Piano Concerto No.21 in C, K.467/list.json"))
                    .findFirst().orElseThrow();
            ListJson.Result r = ListJson.parseFile(p);
            assertThat(r.warnings()).anyMatch(w -> w.contains("mozk467c.mid"));
            assertThat(r.find("MOZK467C.MID").orElseThrow().title()).isEqualTo("3.Allegro vivace assai");
        }
    }
}
