package org.kendar.dlna.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.kendar.dlna.catalog.TrackTags;

/** The optional {@code index} / {@code start} fields of list.json entries that address a section of a file. */
class ListJsonSectionsTest {

    private static ListJson.Result parse(String s) {
        return ListJson.parse(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parsesIndexAndStartAndStaysBackwardCompatible() {
        ListJson.Result r = parse("""
                [
                  {"filename":"disc.flac","title":"Album level","partOf":"Live"},
                  {"filename":"disc.flac","index":2,"start":"3:27.5","title":"Second"},
                  {"filename":"disc.flac#3","title":"Legacy third"},
                  {"filename":"disc.flac","start":"06:00","title":"By start"},
                  {"filename":"old.mid","title":"Old style"}
                ]""");
        assertThat(r.error()).isNull();
        assertThat(r.warnings()).isEmpty();
        assertThat(r.entries()).hasSize(5);
        ListJson.Entry second = r.entries().get(1);
        assertThat(second.index()).isEqualTo(2);
        assertThat(second.start()).isEqualTo("03:27.500");   // normalized
        assertThat(second.isSection()).isTrue();
        assertThat(r.entries().get(0).isSection()).isFalse();
        assertThat(r.entries().get(4).index()).isNull();
        // a whole-file lookup never returns a section entry and vice versa
        assertThat(r.find("disc.flac").orElseThrow().title()).isEqualTo("Album level");
        assertThat(r.find("DISC.flac#2").orElseThrow().title()).isEqualTo("Second");
        assertThat(r.find("disc.flac#3").orElseThrow().title()).isEqualTo("Legacy third");
        assertThat(r.find("old.mid")).isPresent();
    }

    @Test
    void indexAsTextIsAcceptedAndBadLocatorsSkipTheEntryWithAWarning() {
        ListJson.Result r = parse("""
                [
                  {"filename":"a.flac","index":"4","title":"four"},
                  {"filename":"a.flac","index":0,"title":"zero"},
                  {"filename":"a.flac","index":"x","title":"text"},
                  {"filename":"a.flac","index":-2,"title":"negative"},
                  {"filename":"a.flac","start":"soon","title":"bad start"}
                ]""");
        assertThat(r.entries()).extracting(ListJson.Entry::title).containsExactly("four");
        assertThat(r.entries().get(0).index()).isEqualTo(4);
        assertThat(r.warnings()).hasSize(4);
        assertThat(r.warnings().get(0)).contains("invalid index");
        assertThat(r.warnings().get(3)).contains("invalid start");
    }

    @Test
    void theFirstEntryWithTheSameIdentityWins() {
        ListJson.Result r = parse("""
                [
                  {"filename":"a.flac","index":2,"title":"first"},
                  {"filename":"A.FLAC#2","title":"same section, second"},
                  {"filename":"a.flac","index":2,"start":"01:00","title":"index again"},
                  {"filename":"a.flac","start":"01:00","title":"start only"},
                  {"filename":"a.flac","start":"1:00.000","title":"start only again"}
                ]""");
        assertThat(r.entries()).extracting(ListJson.Entry::title).containsExactly("first", "start only");
        assertThat(r.warnings()).hasSize(3).allSatisfy(w -> assertThat(w).contains("duplicate"));
    }

    @Test
    void sectionLookupPrefersTheIndexThenTheClosestStartWithinTheTolerance() {
        ListJson.Result r = parse("""
                [
                  {"filename":"a.flac","index":2,"start":"09:00","title":"index wins"},
                  {"filename":"a.flac","start":"00:03.400","title":"near three"},
                  {"filename":"a.flac","start":"00:03.900","title":"nearer three"},
                  {"filename":"a.flac","start":"00:10","title":"ten"}
                ]""");
        // the index decides even when its start disagrees with the real position
        assertThat(r.findSection("a.flac", 2, 3_493).orElseThrow().title()).isEqualTo("index wins");
        // no index entry: the closest start within 500 ms
        assertThat(r.findSection("a.flac", 7, 3_493).orElseThrow().title()).isEqualTo("near three");
        assertThat(r.findSection("A.FLAC", 7, 3_800).orElseThrow().title()).isEqualTo("nearer three");
        assertThat(r.findSection("a.flac", 7, 10_499).orElseThrow().title()).isEqualTo("ten");
        assertThat(r.findSection("a.flac", 7, 10_501)).isEmpty();
        assertThat(r.findSection("other.flac", 7, 3_493)).isEmpty();
    }

    @Test
    void timesParseAndFormat() {
        assertThat(ListJson.parseTime("03:27.500")).isEqualTo(207_500);
        assertThat(ListJson.parseTime("1:02:03.25")).isEqualTo(3_723_250);
        assertThat(ListJson.parseTime("12.5")).isEqualTo(12_500);
        assertThat(ListJson.parseTime("75:03,120")).isEqualTo(4_503_120);
        assertThat(ListJson.parseTime("7")).isEqualTo(7_000);
        for (String bad : List.of("", "x", "1:2:3:4", "1.2.3", "-1", "00:00:00:00")) {
            assertThat(ListJson.parseTime(bad)).as(bad).isEqualTo(-1);
        }
        assertThat(ListJson.parseTime(null)).isEqualTo(-1);
        assertThat(ListJson.formatTime(207_500)).isEqualTo("03:27.500");
        assertThat(ListJson.formatTime(4_503_120)).isEqualTo("75:03.120");
        assertThat(ListJson.formatTime(0)).isEqualTo("00:00.000");
    }

    @Test
    void locatorsAreNotMetadata() {
        assertThat(new ListJson.Entry("a.flac", null, null, null, null, 2, "00:01.000").isBlank()).isTrue();
        TrackTags tags = new TrackTags("t", "a", "al", null, null, null, 1, null, null, List.of(), List.of(),
                List.of(), null, List.of(), null);
        ListJson.Entry e = new ListJson.Entry("a.flac", "Auth", "Part", "Title", "Tr", 2, "00:01.000");
        TrackTags merged = ListJson.apply(tags, e);
        assertThat(merged.title()).isEqualTo("Title");
        assertThat(merged.artist()).isEqualTo("Auth");
        assertThat(merged.album()).isEqualTo("Part");
        assertThat(merged.transcriber()).isEqualTo("Tr");
    }
}
