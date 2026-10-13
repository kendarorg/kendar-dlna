package org.kendar.dlna.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.Child;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.SortMode;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.catalog.TrackTags;
import org.kendar.dlna.shared.RootRef;

class LibraryScannerTest {

    /** Fake reader: title = file name without extension; fails for files named "broken*". */
    private static final TagReader FAKE = new TagReader() {
        @Override
        public boolean supports(Format format) {
            return format != Format.MIDI && format != Format.OPUS;
        }

        @Override
        public TrackMeta read(Path file, Format format) throws TagReadException {
            String name = file.getFileName().toString();
            if (name.startsWith("broken")) {
                throw new TagReadException("corrupt", null);
            }
            return new TrackMeta(new TrackTags(name.replaceFirst("\\.[^.]*$", ""), "Artist", null, null, null, null,
                    null, null, null), 5000, 128, 44100, 2, name.startsWith("art"));
        }
    };

    @TempDir
    Path tmp;
    Path music;

    @BeforeEach
    void layout() throws IOException {
        music = tmp.resolve("music");
        Files.createDirectories(music.resolve("Rock/Album"));
        Files.createDirectories(music.resolve(".hidden"));
        Files.writeString(music.resolve("a.mp3"), "aaaa");
        Files.writeString(music.resolve("notes.txt"), "ignored");
        Files.writeString(music.resolve("old.alac"), "no decoder");
        Files.writeString(music.resolve("tune.mid"), "midi");
        Files.writeString(music.resolve("broken.flac"), "bad");
        Files.writeString(music.resolve(".secret.mp3"), "hidden");
        Files.writeString(music.resolve(".hidden/x.mp3"), "hidden");
        Files.writeString(music.resolve("Rock/Album/01.FLAC"), "flac");
        Files.writeString(music.resolve("Rock/Album/art.ogg"), "ogg");
        Files.writeString(music.resolve("Rock/Album/Front.JPG"), "jpg");
        Files.writeString(music.resolve("Rock/Album/settings.json"), "{\"sort\":\"title\"}");
        Files.writeString(music.resolve("Rock/settings.json"), "{broken");
    }

    private Snapshot scan() {
        Map<String, Path> roots = new LinkedHashMap<>();
        roots.put("main", music);
        roots.put("missing", tmp.resolve("does-not-exist"));
        return new LibraryScanner(FAKE).scan(roots, 3);
    }

    @Test
    void buildsFoldersAndTracks() {
        Snapshot s = scan();

        assertThat(s.updateId()).isEqualTo(3);
        assertThat(s.roots()).extracting(Folder::ref).containsExactly(RootRef.root("main"));
        assertThat(s.allFolders()).extracting(f -> f.ref().qualified())
                .containsExactlyInAnyOrder("main", "main/Rock", "main/Rock/Album");
        assertThat(s.tracks()).extracting(t -> t.ref().qualified())
                .containsExactlyInAnyOrder("main/a.mp3", "main/Rock/Album/01.FLAC", "main/Rock/Album/art.ogg");

        Track a = s.track(RootRef.parse("main/a.mp3")).orElseThrow();
        assertThat(a.format()).isEqualTo(Format.MP3);
        assertThat(a.size()).isEqualTo(4);
        assertThat(a.durationMillis()).isEqualTo(5000);
        assertThat(a.tags().artist()).isEqualTo("Artist");
        assertThat(s.track(RootRef.parse("main/Rock/Album/art.ogg")).orElseThrow().embeddedArt()).isTrue();
        assertThat(s.track(RootRef.parse("main/Rock/Album/01.FLAC")).orElseThrow().format()).isEqualTo(Format.FLAC);
    }

    @Test
    void recordsUnsupportedAndUnreadableFilesButNotOtherFiles() {
        Snapshot s = scan();

        Map<String, String> reasons = new LinkedHashMap<>();
        s.unsupported().forEach(u -> reasons.put(u.ref().qualified(), u.reason()));
        assertThat(reasons.keySet()).containsExactlyInAnyOrder("main/old.alac", "main/tune.mid", "main/broken.flac");
        assertThat(reasons.get("main/tune.mid")).contains("not supported yet");
        assertThat(reasons.get("main/broken.flac")).contains("unreadable");
    }

    @Test
    void skipsHiddenEntries() {
        Snapshot s = scan();
        assertThat(s.tracks()).noneMatch(t -> t.ref().rel().contains(".secret") || t.ref().rel().contains(".hidden"));
        assertThat(s.allFolders()).noneMatch(f -> f.ref().rel().contains(".hidden"));
    }

    @Test
    void parsesSettingsAndDetectsArtCaseInsensitively() {
        Snapshot s = scan();

        Folder album = s.folder(RootRef.parse("main/Rock/Album")).orElseThrow();
        assertThat(album.settings().sort()).isEqualTo(SortMode.TITLE);
        assertThat(album.settingsError()).isNull();
        assertThat(album.hasFront()).isTrue();
        assertThat(album.hasRear()).isFalse();

        Folder rock = s.folder(RootRef.parse("main/Rock")).orElseThrow();
        assertThat(rock.settingsError()).isNotNull();
        assertThat(rock.settings().sort()).isEqualTo(SortMode.FILENAME);
        assertThat(s.foldersWithSettingsErrors()).containsExactly(rock);
    }

    @Test
    void childrenOfRootAreSortedSubdirsFirst() {
        Snapshot s = scan();
        assertThat(s.children(RootRef.root("main"))).extracting(Child::ref).extracting(RootRef::name)
                .containsExactly("Rock", "a.mp3");
    }

    @Test
    void emptyConfigGivesEmptySnapshot() {
        Snapshot s = new LibraryScanner(FAKE).scan(Map.of(), 1);
        assertThat(s.roots()).isEmpty();
        assertThat(s.tracks()).isEmpty();
        assertThat(Set.copyOf(s.unsupported())).isEmpty();
    }
}
