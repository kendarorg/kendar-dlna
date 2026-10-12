package org.kendar.dlna.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.kendar.dlna.testutil.Fixtures.folder;
import static org.kendar.dlna.testutil.Fixtures.track;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.CatalogStore;
import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.SnapshotHolder;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.catalog.TrackTags;
import org.kendar.dlna.testutil.MutableClock;

class OpusAndMidiIngestionTest {

    @TempDir
    Path tmp;

    static Path resource(String name) throws Exception {
        return Path.of(OpusAndMidiIngestionTest.class.getResource("/audio/" + name).toURI());
    }

    static Path writeMidi(Path file, String trackName, byte[] nameBytes) throws Exception {
        Sequence seq = new Sequence(Sequence.PPQ, 480);
        javax.sound.midi.Track t = seq.createTrack();
        if (trackName != null || nameBytes != null) {
            byte[] data = nameBytes != null ? nameBytes : trackName.getBytes(StandardCharsets.UTF_8);
            t.add(new MidiEvent(new MetaMessage(0x03, data, data.length), 0));
        }
        t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 90), 0));
        t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 60, 0), 960));   // 1 s at 120 bpm
        MidiSystem.write(seq, 1, file.toFile());
        return file;
    }

    @Test
    void opusFixtureGivesExactLengthTagsAndFormat() throws Exception {
        OpusTagReader reader = new OpusTagReader();
        assertThat(reader.supports(Format.OPUS)).isTrue();
        assertThat(reader.supports(Format.OGG)).isFalse();
        TrackMeta m = reader.read(resource("sweep.opus"), Format.OPUS);
        assertThat(m.sampleCount()).isEqualTo(96000);
        assertThat(m.durationMillis()).isEqualTo(2000);
        assertThat(m.sampleRate()).isEqualTo(48000);
        assertThat(m.channels()).isEqualTo(2);
        assertThat(m.bitrateKbps()).isPositive();
        assertThat(m.embeddedArt()).isFalse();
    }

    @Test
    void opusTagsParseMultiValueNumbersAndOddInput() {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes("OpusTags".getBytes(StandardCharsets.US_ASCII));
        writeString(b, "libopus 1.4");
        String[] comments = {"TITLE=Café", "ARTIST=A", "artist=B", "ALBUM=Alb", "ALBUMARTIST=AA", "GENRE=Jazz", "COMPOSER=Ravel", "composer=Satie",
            "DATE=2021-05-01", "TRACKNUMBER=7/12", "DISCNUMBER=1/2", "EMPTY=", "noequals"};
        le32(b, comments.length);
        for (String c : comments) {
            writeString(b, c);
        }
        TrackTags t = OpusTagReader.parseTags(b.toByteArray());
        assertThat(t.title()).isEqualTo("Café");
        assertThat(t.artist()).isEqualTo("A");
        assertThat(t.allArtists()).containsExactly("A", "B");
        assertThat(t.album()).isEqualTo("Alb");
        assertThat(t.albumArtist()).isEqualTo("AA");
        assertThat(t.genre()).isEqualTo("Jazz");
        assertThat(t.composer()).isEqualTo("Ravel");
        assertThat(t.allComposers()).containsExactly("Ravel", "Satie");
        assertThat(t.year()).isEqualTo(2021);
        assertThat(t.trackNo()).isEqualTo(7);
        assertThat(t.discNo()).isEqualTo(1);
        assertThat(t.discTotal()).isEqualTo(2);
        assertThat(OpusTagReader.parseTags(new byte[] {1, 2, 3})).isEqualTo(TrackTags.EMPTY);
    }

    private static void writeString(ByteArrayOutputStream b, String s) {
        byte[] data = s.getBytes(StandardCharsets.UTF_8);
        le32(b, data.length);
        b.writeBytes(data);
    }

    private static void le32(ByteArrayOutputStream b, int v) {
        b.write(v);
        b.write(v >> 8);
        b.write(v >> 16);
        b.write(v >> 24);
    }

    @Test
    void notAnOpusFileIsReportedAsUnreadable() throws Exception {
        Path junk = Files.write(tmp.resolve("x.opus"), new byte[300]);
        assertThatThrownBy(() -> new OpusTagReader().read(junk, Format.OPUS))
                .isInstanceOf(TagReader.TagReadException.class);
        Path ogg = resource("sweep.ogg");
        assertThatThrownBy(() -> new OpusTagReader().read(ogg, Format.OPUS))
                .isInstanceOf(TagReader.TagReadException.class);
    }

    @Test
    void midiTitleComesFromTheTrackNameEventAndDurationFromTheSequence() throws Exception {
        Path mid = writeMidi(tmp.resolve("song.mid"), "Prelude in C", null);
        TrackMeta m = new MidiTagReader(true, "").read(mid, Format.MIDI);
        assertThat(m.tags().title()).isEqualTo("Prelude in C");
        assertThat(m.durationMillis()).isBetween(990L, 1010L);
        assertThat(m.sampleCount()).isZero();
        Path latin = writeMidi(tmp.resolve("latin.mid"), null, new byte[] {'C', 'a', 'f', (byte) 0xe9});
        assertThat(new MidiTagReader(true, "").read(latin, Format.MIDI).tags().title()).isEqualTo("Café");
        Path none = writeMidi(tmp.resolve("none.mid"), null, null);
        assertThat(new MidiTagReader(true, "").read(none, Format.MIDI).tags().title()).isNull();
        Path blank = writeMidi(tmp.resolve("blank.mid"), "  ", null);
        assertThat(new MidiTagReader(true, "").read(blank, Format.MIDI).tags().title()).isNull();
    }

    @Test
    void corruptMidiIsUnreadable() throws Exception {
        Path bad = Files.write(tmp.resolve("bad.mid"), new byte[] {1, 2, 3, 4, 5});
        assertThatThrownBy(() -> new MidiTagReader(true, "").read(bad, Format.MIDI))
                .isInstanceOf(TagReader.TagReadException.class);
    }

    @Test
    void midiIsUnsupportedWithAClearReasonWhenRenderingIsUnavailable() throws Exception {
        MidiTagReader off = new MidiTagReader(false, "no SoundFont configured");
        assertThat(off.supports(Format.MIDI)).isFalse();
        assertThat(off.owns(Format.MIDI)).isTrue();
        TagReader all = new CompositeTagReader(new JAudioTaggerReader(), new OpusTagReader(), off);
        assertThat(all.supports(Format.MIDI)).isFalse();
        assertThat(all.unsupportedReason(Format.MIDI)).contains("no SoundFont configured");
        assertThat(all.supports(Format.OPUS)).isTrue();
        assertThat(all.supports(Format.FLAC)).isTrue();

        Path music = Files.createDirectories(tmp.resolve("music"));
        writeMidi(music.resolve("song.mid"), "T", null);
        Files.copy(resource("sweep.opus"), music.resolve("a.opus"));
        Map<String, Path> roots = new LinkedHashMap<>();
        roots.put("m", music);
        Snapshot s = new LibraryScanner(all).scan(roots, 1);
        assertThat(s.unsupported()).singleElement().satisfies(u -> {
            assertThat(u.ref().name()).isEqualTo("song.mid");
            assertThat(u.reason()).contains("MIDI rendering unavailable").contains("no SoundFont configured");
        });
        Track opus = s.tracks().get(0);
        assertThat(opus.format()).isEqualTo(Format.OPUS);
        assertThat(opus.sampleCount()).isEqualTo(96000);
        assertThat(opus.durationMillis()).isEqualTo(2000);

        TagReader on = new CompositeTagReader(new JAudioTaggerReader(), new OpusTagReader(),
                new MidiTagReader(true, ""));
        Snapshot withMidi = new LibraryScanner(on).scan(roots, 1);
        assertThat(withMidi.unsupported()).isEmpty();
        assertThat(withMidi.tracks()).extracting(Track::format).containsExactlyInAnyOrder(Format.OPUS, Format.MIDI);
        assertThat(withMidi.tracks().stream().filter(t -> t.format() == Format.MIDI).findFirst().orElseThrow()
                .tags().title()).isEqualTo("T");
    }

    @Test
    void tagEditingIsDisabledForOpusAndRawAdtsOnlyAndMidiHoldsThreeFields() {
        for (Format f : Format.values()) {
            assertThat(f.tagsEditable()).as(f.name()).isEqualTo(f != Format.OPUS && f != Format.ADTS && f != Format.ABC && !f.needsFfmpeg());
        }
    }

    @Test
    void renderedLengthIsRecordedPersistedAndBumpsTheContainer() throws IOException {
        Track midi = track("m/tune.mid");
        MutableClock clock = new MutableClock();
        CatalogStoreSpy store = new CatalogStoreSpy();
        CatalogService service = new CatalogService(new SnapshotHolder(
                new Snapshot(3, Instant.EPOCH, List.of(folder("m")), List.of(midi), List.of(), List.of())), store, clock);
        List<Long> events = new java.util.ArrayList<>();
        service.addListener(c -> events.add(c.systemUpdateId()));

        assertThat(service.recordRenderedLength(midi, 480_100, 10_002)).isTrue();
        Track now = service.current().track(midi.ref()).orElseThrow();
        assertThat(now.sampleCount()).isEqualTo(480_100);
        assertThat(now.renderedMillis()).isEqualTo(10_002);
        assertThat(service.current().updateId()).isEqualTo(4);
        assertThat(events).containsExactly(4L);
        assertThat(store.saved.track(midi.ref()).orElseThrow().sampleCount()).isEqualTo(480_100);

        assertThat(service.recordRenderedLength(midi, 480_100, 10_002)).as("unchanged").isFalse();
        assertThat(service.current().updateId()).isEqualTo(4);

        Track other = new Track(midi.ref(), midi.mtime() + 1, midi.size(), midi.format(), midi.tags(),
                midi.durationMillis(), 0, 0, 0, false);
        assertThat(service.recordRenderedLength(other, 5, 5)).as("file changed meanwhile").isFalse();
        assertThat(service.recordRenderedLength(track("m/gone.mid"), 5, 5)).isFalse();
        assertThat(service.current().track(midi.ref()).orElseThrow().sampleCount()).isEqualTo(480_100);
    }

    private static final class CatalogStoreSpy implements CatalogStore {
        Snapshot saved;

        @Override
        public java.util.Optional<Snapshot> load() {
            return java.util.Optional.ofNullable(saved);
        }

        @Override
        public void save(Snapshot snapshot) {
            saved = snapshot;
        }
    }
}
