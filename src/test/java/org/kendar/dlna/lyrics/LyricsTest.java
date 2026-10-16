package org.kendar.dlna.lyrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.Track;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.curation.CurationException;
import org.kendar.dlna.shared.RootRef;
import org.kendar.dlna.testutil.AudioFiles;
import org.kendar.dlna.testutil.LibraryFixture;

class LyricsTest {

    @TempDir
    Path tmp;

    // ---- the LRC parser ----

    @Test
    void parsesTimedLinesWithSeveralTagsAndAnOffset() {
        Lyrics l = LrcParser.parse("[ar:Someone]\n[offset:+500]\n[00:01.50]Hello\n[00:10.00][00:20.5]Again\n", "lrc");

        assertThat(l.synced()).isTrue();
        assertThat(l.lines()).extracting(Lyrics.Line::startMillis).containsExactly(1000L, 9500L, 20000L);
        assertThat(l.lines()).extracting(Lyrics.Line::text).containsExactly("Hello", "Again", "Again");
        assertThat(l.toLrc()).startsWith("[00:01.00]Hello\n");
    }

    @Test
    void textWithoutTimeTagsIsPlain() {
        Lyrics l = LrcParser.parse("Line one\r\nLine two\r\n\r\n", "txt");

        assertThat(l.synced()).isFalse();
        assertThat(l.lines()).extracting(Lyrics.Line::text).containsExactly("Line one", "Line two");
        assertThat(l.toLrc()).isEqualTo("Line one\nLine two\n");
        assertThat(LrcParser.parse("[ti:Only a header]\n", "lrc").isEmpty()).isTrue();
    }

    // ---- what a track already has ----

    @Test
    void aSidecarLrcBeatsATxtAndWindows1252Works() throws IOException {
        Path audio = tmp.resolve("song.mp3");
        Files.write(audio, new byte[] { 0 });
        Files.write(tmp.resolve("song.txt"), "plain".getBytes());
        assertThat(LocalLyrics.read(audio, Format.MP3).orElseThrow().synced()).isFalse();

        Files.write(tmp.resolve("song.lrc"), "[00:02.00]café".getBytes(Charset.forName("windows-1252")));
        Lyrics l = LocalLyrics.read(audio, Format.MP3).orElseThrow();
        assertThat(l.source()).isEqualTo("lrc");
        assertThat(l.lines().get(0).text()).isEqualTo("café");
        assertThat(LocalLyrics.read(tmp.resolve("nothing.mp3"), Format.MP3)).isEmpty();
    }

    @Test
    void embeddedLyricsAreReadFromTheTag() throws Exception {
        Path flac = tmp.resolve("tagged.flac");
        AudioFiles.fixture("sweep.flac", flac);
        AudioFile af = AudioFileIO.read(flac.toFile());
        af.getTagOrCreateAndSetDefault().setField(FieldKey.LYRICS, "[00:03.00]Inside the file");
        af.commit();

        Lyrics l = LocalLyrics.read(flac, Format.FLAC).orElseThrow();

        assertThat(l.source()).isEqualTo("tag");
        assertThat(l.synced()).isTrue();
        assertThat(l.lines().get(0).startMillis()).isEqualTo(3000L);
    }

    @Test
    void karaokeLyricEventsAreTimedWithTheTempoMap() throws Exception {
        Sequence seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        track.add(new javax.sound.midi.MidiEvent(new MetaMessage(0x51, new byte[] { 0x07, (byte) 0xA1, 0x20 }, 3), 0));   // 500000 us
        track.add(new javax.sound.midi.MidiEvent(lyric("Hel"), 0));
        track.add(new javax.sound.midi.MidiEvent(lyric("lo/"), 480));      // 500 ms
        track.add(new javax.sound.midi.MidiEvent(lyric("World"), 960));    // 1000 ms
        Path kar = tmp.resolve("song.kar");
        try (var out = Files.newOutputStream(kar)) {
            MidiSystem.write(seq, 1, out);
        }

        Lyrics l = LocalLyrics.read(kar, Format.MIDI).orElseThrow();

        assertThat(l.source()).isEqualTo("kar");
        assertThat(l.lines()).extracting(Lyrics.Line::text).containsExactly("Hello", "World");
        assertThat(l.lines()).extracting(Lyrics.Line::startMillis).containsExactly(0L, 1000L);
    }

    private static MetaMessage lyric(String text) throws Exception {
        byte[] b = text.getBytes();
        return new MetaMessage(0x05, b, b.length);
    }

    // ---- the provider ----

    @Test
    void lrclibPrefersSyncedLyricsAndFallsBackToSearchOn404() throws Exception {
        List<String> urls = new ArrayList<>();
        LyricsHttp.Get http = uri -> {
            urls.add(uri.toString());
            if (uri.getPath().endsWith("/get")) {
                throw new LyricsHttp.NotFound("no");
            }
            return "[{\"duration\":200,\"plainLyrics\":\"just text\"},"
                    + "{\"duration\":201,\"syncedLyrics\":\"[00:01.00]Synced\",\"plainLyrics\":\"Synced\"},"
                    + "{\"duration\":999,\"syncedLyrics\":\"[00:01.00]Other recording\"}]";
        };
        LrclibProvider p = new LrclibProvider("https://x.example/api/", http);

        List<Lyrics> found = p.find(new LyricsProvider.Query("Ann Lee", "A Song", "Blue", 200));

        assertThat(urls.get(0)).startsWith("https://x.example/api/get?track_name=A+Song&artist_name=Ann+Lee")
                .contains("album_name=Blue").contains("duration=200");
        assertThat(found).hasSize(2);
        assertThat(found.get(0).synced()).isTrue();
        assertThat(found.get(0).lines().get(0).text()).isEqualTo("Synced");
        assertThat(found.stream().map(Lyrics::text)).doesNotContain("Other recording");
        assertThat(p.find(new LyricsProvider.Query("x", " ", null, 0))).isEmpty();
    }

    @Test
    void lrclibReportsServiceErrors() {
        LrclibProvider p = new LrclibProvider(null, (URI u) -> {
            throw new IOException("lyrics service answered 500");
        });
        assertThatThrownBy(() -> p.find(new LyricsProvider.Query("a", "b", null, 0))).isInstanceOf(IOException.class);
    }

    @Test
    void lrclibTriesAFreeTextSearchAndKeepsGoingWhenAStepFails() throws Exception {
        List<String> urls = new ArrayList<>();
        LyricsHttp.Get http = uri -> {
            urls.add(uri.toString());
            if (uri.getPath().endsWith("/get")) {
                throw new IOException("lyrics service answered 503");
            }
            if (uri.getQuery().startsWith("track_name=")) {
                return "[]";
            }
            return "[{\"duration\":200,\"syncedLyrics\":\"[00:01.00]Found by q\"}]";
        };
        List<Lyrics> found = new LrclibProvider("https://x.example/api", http)
                .find(new LyricsProvider.Query("Ann Lee", "A Song", null, 200));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).lines().get(0).text()).isEqualTo("Found by q");
        assertThat(urls).hasSize(3);
        assertThat(urls.get(2)).isEqualTo("https://x.example/api/search?q=Ann+Lee+A+Song");
    }

    @Test
    void lyricsOvhGivesPlainLyricsFromArtistAndTitle() throws Exception {
        List<String> urls = new ArrayList<>();
        LyricsOvhProvider p = new LyricsOvhProvider("https://o.example/v1/", uri -> {
            urls.add(uri.toString());
            return "{\"lyrics\":\"Paroles de la chanson A Song par Ann Lee\\r\\nLine one\\nLine two\"}";
        });

        List<Lyrics> found = p.find(new LyricsProvider.Query("Ann Lee", "A Song", null, 0));

        assertThat(urls).containsExactly("https://o.example/v1/Ann%20Lee/A%20Song");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).synced()).isFalse();
        assertThat(found.get(0).lines()).extracting(Lyrics.Line::text).containsExactly("Line one", "Line two");
        assertThat(p.find(new LyricsProvider.Query(" ", "A Song", null, 0))).isEmpty();
        assertThat(new LyricsOvhProvider(null, (URI u) -> {
            throw new LyricsHttp.NotFound("no");
        }).find(new LyricsProvider.Query("a", "b", null, 0))).isEmpty();
    }

    @Test
    void aChainAsksEveryoneAtOnceAndReturnsEverythingSyncedFirstWithoutDuplicates() throws Exception {
        Lyrics synced = LrcParser.parse("[00:01.00]Synced", "a");
        Lyrics plain = LrcParser.parse("Plain", "b");
        List<LyricsProvider.Query> askedB = new ArrayList<>();
        LyricsProvider failing = new LyricsProvider() {
            public String id() {
                return "down";
            }

            public List<Lyrics> find(Query q) throws IOException {
                throw new IOException("lyrics service answered 503");
            }
        };
        LyricsProvider.Query q = new LyricsProvider.Query("a", "b", null, 0);

        assertThat(new ChainProvider(List.of(fake(List.of(synced), new ArrayList<>()), fake(List.of(plain), askedB)))
                .find(q)).containsExactly(synced, plain);   // synced first, the plain one is offered too
        assertThat(askedB).hasSize(1);
        askedB.clear();
        Lyrics samePlain = LrcParser.parse("plain", "c");
        assertThat(new ChainProvider(List.of(fake(List.of(plain), new ArrayList<>()), fake(List.of(samePlain), askedB)))
                .find(q)).containsExactly(plain);   // the same text from two providers only once

        LyricsProvider slow = new LyricsProvider() {
            public String id() {
                return "slow";
            }

            public List<Lyrics> find(Query query) throws IOException {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
                return List.of(LrcParser.parse("slow " + Thread.currentThread().threadId(), "slow"));
            }
        };
        long start = System.nanoTime();
        assertThat(new ChainProvider(List.of(slow, slow, slow)).find(q)).hasSize(3);
        assertThat((System.nanoTime() - start) / 1_000_000).as("asked in parallel").isLessThan(1000);
        askedB.clear();
        assertThat(new ChainProvider(List.of(failing, fake(List.of(plain), askedB))).find(q)).containsExactly(plain);
        assertThat(askedB).hasSize(1);
        assertThatThrownBy(() -> new ChainProvider(List.of(failing, failing)).find(q)).isInstanceOf(IOException.class)
                .hasMessageContaining("down").hasMessageContaining("503");
        // one provider found nothing but another could not answer: that is a failure to retry, not "not found"
        assertThatThrownBy(() -> new ChainProvider(List.of(failing, fake(List.of(), new ArrayList<>()))).find(q))
                .isInstanceOf(IOException.class);
        assertThat(new ChainProvider(List.of(fake(List.of(), new ArrayList<>()), fake(List.of(), new ArrayList<>())))
                .find(q)).isEmpty();
        assertThat(LyricsProviders.ids(" LRCLIB, lyrics.ovh ,lrclib")).containsExactly("lrclib", "lyrics.ovh");
        assertThat(LyricsProviders.create("lrclib,lyrics.ovh", java.util.Map.of(), java.time.Duration.ofSeconds(1), "t")
                .id()).isEqualTo("lrclib,lyrics.ovh");
    }

    @Test
    void lrcCxReturnsTheSyncedLrcTextOrNothingOn404() throws Exception {
        List<String> urls = new ArrayList<>();
        LrcCxProvider p = new LrcCxProvider("https://c.example/", uri -> {
            urls.add(uri.toString());
            return "[Verse]\n[00:04.89]Yesterday\n[00:08.01]All my troubles";
        });

        List<Lyrics> found = p.find(new LyricsProvider.Query("The Beatles", "Yesterday", null, 0));

        assertThat(urls).containsExactly("https://c.example/lyrics?title=Yesterday&artist=The+Beatles");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).synced()).isTrue();
        assertThat(found.get(0).lines()).extracting(Lyrics.Line::text).containsExactly("Yesterday", "All my troubles");
        assertThat(new LrcCxProvider(null, (URI u) -> {
            throw new LyricsHttp.NotFound("no");
        }).find(new LyricsProvider.Query("a", "b", null, 0))).isEmpty();
    }

    @Test
    void neteaseKeepsTheHitsOfTheSameTitleAndLengthAndTakesTheFirstWithLyrics() throws Exception {
        List<String> urls = new ArrayList<>();
        LyricsHttp.Get http = uri -> {
            urls.add(uri.toString());
            if (uri.getPath().endsWith("/search/get")) {
                return "{\"result\":{\"songs\":["
                        + "{\"id\":1,\"name\":\"Something else\",\"duration\":200000},"
                        + "{\"id\":2,\"name\":\"A Song (Live)\",\"duration\":400000},"
                        + "{\"id\":3,\"name\":\"A Song\",\"duration\":201000},"
                        + "{\"id\":4,\"name\":\"A Song\",\"duration\":199000}]}}";
            }
            return uri.getQuery().contains("id=3") ? "{\"lrc\":{\"lyric\":\"\"}}"
                    : "{\"lrc\":{\"lyric\":\"[00:01.00]Ciao\"}}";
        };

        List<Lyrics> found = new NeteaseProvider("https://n.example/api", http)
                .find(new LyricsProvider.Query("Ann", "A Song", null, 200));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).lines().get(0).text()).isEqualTo("Ciao");
        assertThat(urls).hasSize(3);   // one search, the lyrics of hit 3 (empty) and of hit 4
        assertThat(urls.get(1)).endsWith("id=3");
        assertThat(urls.get(2)).endsWith("id=4");
    }

    // ---- the service ----

    private static LyricsProvider fake(List<Lyrics> answer, List<LyricsProvider.Query> asked) {
        return new LyricsProvider() {
            @Override
            public String id() {
                return "fake";
            }

            @Override
            public List<Lyrics> find(Query q) {
                asked.add(q);
                return answer;
            }
        };
    }


    @Test
    void aLookupShowsEachProvidersLyricsAsSoonAsTheyArriveAndOnlyAppends() throws Exception {
        LibraryFixture lib = new LibraryFixture(tmp);
        Lyrics fastLyrics = LrcParser.parse("Fast one\nFast two", "fast");
        Lyrics slowLyrics = LrcParser.parse("[00:01.00]Slow", "slow");
        LyricsProvider fast = namedProvider("fast", 0, fastLyrics, null);
        LyricsProvider slow = namedProvider("slow", 600, slowLyrics, null);
        LyricsProvider broken = namedProvider("broken", 0, null, "lyrics service answered 503");
        LyricsService svc = new LyricsService(lib.catalog::current, lib.files,
                new ChainProvider(List.of(slow, fast, broken)), 0);

        LyricsService.Lookup job = svc.startLookup(RootRef.parse("main/Album/03 three.mp3"));

        // the fast and the broken providers answer first; the slow one is still pending
        LyricsService.LookupView early = job.view(0);
        for (int i = 0; i < 100 && early.candidates().isEmpty(); i++) {
            Thread.sleep(20);
            early = job.view(0);
        }
        assertThat(early.running()).isTrue();
        assertThat(early.candidates()).containsExactly(fastLyrics);
        assertThat(early.providers()).extracting(LyricsService.ProviderState::id).containsExactly("slow", "fast", "broken");
        assertThat(early.providers()).extracting(LyricsService.ProviderState::state)
                .containsExactly("pending", "done", "failed");

        LyricsService.LookupView late = job.view(0);
        for (int i = 0; i < 200 && late.running(); i++) {
            Thread.sleep(20);
            late = job.view(0);
        }
        assertThat(late.running()).isFalse();
        assertThat(late.candidates()).containsExactly(fastLyrics, slowLyrics);   // appended: index 0 did not move
        assertThat(job.view(1).candidates()).containsExactly(slowLyrics);        // only what is new since the first one
        assertThat(job.view(2).candidates()).isEmpty();
        assertThat(svc.lookupJob(late.id())).isPresent();
        assertThat(svc.lookupJob("nope")).isEmpty();
    }

    private static LyricsProvider namedProvider(String id, long delayMillis, Lyrics answer, String failure) {
        return new LyricsProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public List<Lyrics> find(Query q) throws IOException {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
                if (failure != null) {
                    throw new IOException(failure);
                }
                return List.of(answer);
            }
        };
    }
    @Test
    void lookupSavesASidecarAndNothingElseWithoutAProvider() throws IOException {
        LibraryFixture lib = new LibraryFixture(tmp);
        List<LyricsProvider.Query> asked = new ArrayList<>();
        Lyrics synced = LrcParser.parse("[00:01.00]La la", "fake");
        LyricsService svc = new LyricsService(lib.catalog::current, lib.files, fake(List.of(synced), asked), 0);
        RootRef ref = RootRef.parse("main/Album/03 three.mp3");

        assertThat(svc.local(ref)).isEmpty();
        assertThat(svc.lookup(ref)).containsExactly(synced);
        assertThat(asked.get(0).title()).isNotBlank();
        assertThat(svc.save(ref, synced)).isEqualTo("03 three.lrc");
        assertThat(Files.readString(lib.music.resolve("Album/03 three.lrc"))).isEqualTo("[00:01.00]La la\n");
        assertThat(svc.local(ref).orElseThrow().synced()).isTrue();
        assertThat(svc.save(ref, LrcParser.parse("just words", "x"))).isEqualTo("03 three.txt");

        LyricsService off = new LyricsService(lib.catalog::current, lib.files, null, 0);
        assertThat(off.providerId()).isNull();
        assertThatThrownBy(() -> off.lookup(ref)).isInstanceOf(CurationException.class);
        assertThatThrownBy(() -> off.startFolder(RootRef.parse("main/Album"), false)).isInstanceOf(CurationException.class);
        assertThatThrownBy(() -> svc.save(RootRef.parse("main/Nope.mp3"), synced)).isInstanceOf(CurationException.class);
    }

    @Test
    void theFolderJobReviewsEveryTrackAndOnlyTheAppliedOnesAreWritten() throws Exception {
        LibraryFixture lib = new LibraryFixture(tmp);
        Files.writeString(lib.music.resolve("Album/01 one.lrc"), "[00:01.00]Already there");
        Lyrics a = LrcParser.parse("[00:01.00]One", "fake");
        Lyrics b = LrcParser.parse("Two different", "fake");
        int[] call = { 0 };
        LyricsProvider provider = new LyricsProvider() {
            @Override
            public String id() {
                return "fake";
            }

            @Override
            public List<Lyrics> find(Query q) throws IOException {
                return switch (call[0]++) {
                    case 0 -> List.of(a);
                    case 1 -> List.of(a, b);
                    default -> List.of();
                };
            }
        };
        LyricsService svc = new LyricsService(lib.catalog::current, lib.files, provider, 0);

        svc.startFolder(RootRef.parse("main/Album"), false);
        for (int i = 0; i < 200 && svc.status().running(); i++) {
            Thread.sleep(25);
        }

        LyricsService.Status st = svc.status();
        assertThat(st.running()).isFalse();
        assertThat(st.done()).isEqualTo(4);
        assertThat(st.items()).extracting(LyricsService.Item::outcome).containsExactlyInAnyOrder(
                LyricsService.Outcome.HAS_LYRICS, LyricsService.Outcome.MATCHED, LyricsService.Outcome.AMBIGUOUS,
                LyricsService.Outcome.NOT_FOUND);
        assertThat(lib.music.resolve("Album")).isDirectory();
        long lrcBefore = Files.list(lib.music.resolve("Album")).filter(p -> p.toString().endsWith(".lrc")).count();
        assertThat(lrcBefore).as("nothing is written before apply").isEqualTo(1);

        String matched = st.items().stream().filter(i -> i.outcome() == LyricsService.Outcome.MATCHED).findFirst()
                .orElseThrow().path();
        assertThat(svc.applyFolder(List.of(matched))).containsExactly(matched);
        assertThat(svc.applyFolder(List.of(matched))).as("applied once").isEmpty();
        assertThat(svc.status().items().stream().filter(i -> i.path().equals(matched)).findFirst().orElseThrow()
                .applied()).isTrue();
        assertThat(Files.list(lib.music.resolve("Album")).filter(p -> p.toString().endsWith(".lrc")).count())
                .isEqualTo(2);
    }
}
