package org.kendar.dlna.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Parsing, naming and discovery of the per-file {@code <file>.json} sections lists. */
class SidecarJsonTest {

    @TempDir
    Path tmp;

    private static ListJson.Result parse(String text) {
        return SidecarJson.parse(text.getBytes(StandardCharsets.UTF_8), "concert.flac");
    }

    @Test
    void theSidecarIsTheFullFileNamePlusJson() {
        assertThat(SidecarJson.path(Path.of("dir", "concert.flac")).getFileName().toString())
                .isEqualTo("concert.flac.json");
        assertThat(SidecarJson.isSidecarName("concert.flac.json")).isTrue();
        assertThat(SidecarJson.isSidecarName("Concert.FLAC.JSON")).isTrue();
        assertThat(SidecarJson.isSidecarName("list.json")).isFalse();
        assertThat(SidecarJson.isSidecarName("settings.json")).isFalse();
        assertThat(SidecarJson.isSidecarName(".hidden.flac.json")).isFalse();
        assertThat(SidecarJson.isSidecarName("concert.flac")).isFalse();
        assertThat(SidecarJson.isSidecarName(null)).isFalse();
    }

    @Test
    void filenameIsOptionalAndDefaultsToTheAudioFile() {
        ListJson.Result r = parse("""
                [ {"partOf":"Album"},
                  {"index":1,"title":"One"},
                  {"filename":"CONCERT.flac","index":2,"start":"3:07.5","title":"Two"} ]""");
        assertThat(r.error()).isNull();
        assertThat(r.warnings()).isEmpty();
        assertThat(r.entries()).extracting(ListJson.Entry::filename).containsOnly("concert.flac");
        assertThat(r.entries()).extracting(ListJson.Entry::index).containsExactly(null, 1, 2);
        assertThat(r.entries().get(2).start()).isEqualTo("03:07.500");
        assertThat(r.find("concert.flac").orElseThrow().partOf()).isEqualTo("Album");   // album level entry
        assertThat(r.findSection("concert.flac", 2, 0).orElseThrow().title()).isEqualTo("Two");
    }

    @Test
    void entriesAboutOtherFilesAreSkippedWithAWarningAndTheLegacyHashNameIsKept() {
        ListJson.Result r = parse("""
                [ {"filename":"other.flac","title":"Nope"},
                  {"filename":"concert.flac#3","title":"Legacy"},
                  {"title":"Mine"} ]""");
        assertThat(r.entries()).extracting(ListJson.Entry::title).containsExactly("Legacy", "Mine");
        assertThat(r.warnings()).singleElement().asString().contains("other.flac").contains("concert.flac");
        assertThat(r.findSection("concert.flac", 3, 0).orElseThrow().title()).isEqualTo("Legacy");
    }

    @Test
    void parsingIsAsTolerantAsListJson() {
        ListJson.Result r = parse("﻿// my sections\n[ {'index':'1', title:'One',}, 7, {\"index\":\"x\"}, ]");
        assertThat(r.error()).isNull();
        assertThat(r.entries()).hasSize(1);
        assertThat(r.entries().get(0).title()).isEqualTo("One");
        assertThat(r.warnings()).hasSize(2);   // the number and the bad index
        assertThat(parse("[{\"index\":1,\"title\":\"a\"},{\"index\":1,\"title\":\"b\"}]").entries()).hasSize(1);
    }

    @Test
    void aBareObjectIsOneEntryAndTracksWrapperIsAccepted() {
        assertThat(parse("{\"partOf\":\"Album\",\"author\":\"Band\"}").entries()).singleElement()
                .satisfies(e -> {
                    assertThat(e.filename()).isEqualTo("concert.flac");
                    assertThat(e.isSection()).isFalse();
                });
        assertThat(parse("{\"tracks\":[{\"index\":1,\"title\":\"a\"}]}").entries()).hasSize(1);
    }

    @Test
    void garbageOrEmptyFilesNeverThrow() {
        ListJson.Result broken = parse("[{\"filename\": ");
        assertThat(broken.entries()).isEmpty();
        assertThat(broken.error()).startsWith("malformed JSON");
        assertThat(broken.hash()).isNotNull();
        assertThat(parse("").entries()).isEmpty();
        assertThat(parse("").error()).isNull();
        assertThat(parse("42").error()).isNotNull();
    }

    @Test
    void readsTheSidecarNextToTheFileInAnyLetterCase() throws IOException {
        Path audio = tmp.resolve("concert.flac");
        Files.writeString(audio, "x");
        assertThat(SidecarJson.read(audio)).isEqualTo(ListJson.Result.NONE);
        assertThat(SidecarJson.locate(audio)).isEmpty();

        Files.writeString(tmp.resolve("Concert.FLAC.JSON"), "[{\"index\":1,\"title\":\"One\"}]");
        assertThat(SidecarJson.locate(audio)).isPresent();
        ListJson.Result r = SidecarJson.read(audio);
        assertThat(r.exists()).isTrue();
        assertThat(r.entries()).singleElement().satisfies(e -> assertThat(e.title()).isEqualTo("One"));
    }

    @Test
    void applyFromDiskPrefersTheSidecarOverTheDirectoryListJson() throws IOException {
        Path audio = tmp.resolve("concert.flac");
        Files.writeString(audio, "x");
        Files.writeString(tmp.resolve("list.json"), "[{\"filename\":\"concert.flac\",\"title\":\"From list\"}]");
        var tags = new org.kendar.dlna.catalog.TrackTags("Tag", null, null, null, null, null, null, null, null,
                java.util.List.of(), java.util.List.of(), java.util.List.of(), null, java.util.List.of(), null);
        assertThat(ListJson.applyFromDisk(audio, tags).title()).isEqualTo("From list");

        Files.writeString(tmp.resolve("concert.flac.json"), "[{\"title\":\"From sidecar\"}]");
        assertThat(ListJson.applyFromDisk(audio, tags).title()).isEqualTo("From sidecar");

        Files.writeString(tmp.resolve("concert.flac.json"), "[]");   // an empty sidecar says nothing
        assertThat(ListJson.applyFromDisk(audio, tags).title()).isEqualTo("From list");
    }

    @Test
    void directoryListsCombineTheHashAndPickTheSidecarPerFile() throws IOException {
        Files.writeString(tmp.resolve("a.flac"), "x");
        Files.writeString(tmp.resolve("b.flac"), "x");
        Files.writeString(tmp.resolve("stray.flac.json"), "[{\"title\":\"no audio\"}]");
        assertThat(DirectoryLists.read(tmp).hash()).isNull();
        assertThat(DirectoryLists.read(tmp).sidecars()).isEmpty();

        Files.writeString(tmp.resolve("list.json"), "[{\"filename\":\"a.flac\",\"title\":\"dir a\"}]");
        String dirOnly = DirectoryLists.read(tmp).hash();
        assertThat(dirOnly).isEqualTo(ListJson.forDirectory(tmp).hash());   // no sidecar: unchanged behaviour

        Files.writeString(tmp.resolve("a.flac.json"), "[{\"title\":\"side a\"}]");
        DirectoryLists l = DirectoryLists.read(tmp);
        assertThat(l.hash()).isNotEqualTo(dirOnly);
        assertThat(l.forFile("a.flac").find("a.flac").orElseThrow().title()).isEqualTo("side a");
        assertThat(l.forFile("b.flac")).isSameAs(l.directory());
        assertThat(l.sidecarFiles()).containsExactly("a.flac");

        String one = l.hash();
        Files.writeString(tmp.resolve("a.flac.json"), "[{\"title\":\"side a2\"}]");
        assertThat(DirectoryLists.read(tmp).hash()).isNotEqualTo(one);
        Files.delete(tmp.resolve("a.flac.json"));
        assertThat(DirectoryLists.read(tmp).hash()).isEqualTo(dirOnly);

        // an unreadable sidecar is a problem, and the directory list applies again
        Files.writeString(tmp.resolve("a.flac.json"), "[{");
        l = DirectoryLists.read(tmp);
        assertThat(l.problems()).singleElement().asString().startsWith("a.flac.json: malformed JSON");
        assertThat(l.forFile("a.flac")).isSameAs(l.directory());
    }
}
