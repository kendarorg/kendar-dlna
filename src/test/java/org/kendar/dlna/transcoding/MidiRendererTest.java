package org.kendar.dlna.transcoding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import org.junit.jupiter.api.Test;

/** Spike item 2: Gervill offline at 48 kHz, no audio device, generated MIDI. */
class MidiRendererTest {

    /** C major arpeggio, 120 bpm, PPQ 480: one note per beat on program {@code program}. */
    static Sequence arpeggio(int program, int notes) throws Exception {
        Sequence seq = new Sequence(Sequence.PPQ, 480);
        Track t = seq.createTrack();
        t.add(new MidiEvent(new MetaMessage(0x51, new byte[] {0x07, (byte) 0xA1, 0x20}, 3), 0));
        t.add(new MidiEvent(new ShortMessage(ShortMessage.PROGRAM_CHANGE, 0, program, 0), 0));
        int[] scale = {60, 64, 67, 72};
        for (int i = 0; i < notes; i++) {
            long on = i * 480L;
            t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, scale[i % 4], 100), on));
            t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, scale[i % 4], 0), on + 400));
        }
        return seq;
    }

    @Test
    void timelineAppliesTempoChangesAndDropsMetaMessages() throws Exception {
        Sequence seq = new Sequence(Sequence.PPQ, 480);
        Track t = seq.createTrack();
        t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 100), 0));
        // at tick 480 (0.5 s at the default 120 bpm) the tempo drops to 60 bpm
        t.add(new MidiEvent(new MetaMessage(0x51, new byte[] {0x0F, 0x42, 0x40}, 3), 480));
        t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 60, 0), 960));
        var events = MidiRenderer.timeline(seq);
        assertThat(events).hasSize(2);
        assertThat(events.get(0).micros()).isZero();
        assertThat(events.get(1).micros()).isEqualTo(500_000 + 1_000_000);
    }

    @Test
    void rendersHeadlessAt48kWithTheBuiltInSoundbankAndTrimsTheTail() throws Exception {
        assumeTrue(MidiRenderer.isAvailable(), "needs --add-exports java.desktop/com.sun.media.sound");
        long t0 = System.nanoTime();
        MidiRenderer.Result r = new MidiRenderer(null).render(arpeggio(0, 4));
        double secs = (System.nanoTime() - t0) / 1e9;
        short[] s = TestAudio.toShorts(r.pcm());
        int peak = 0;
        for (short v : s) {
            peak = Math.max(peak, Math.abs(v));
        }
        System.out.printf("[spike] built-in soundbank: sequence %.2f s, event end %.2f s, rendered+trimmed %.3f s, "
                        + "peak %d, wall %.2f s%n",
                r.sequenceFrames() / 48000.0, r.eventEndFrame() / 48000.0, r.frames() / 48000.0, peak, secs);
        assertThat(r.sampleRate()).isEqualTo(48000);
        assertThat(peak).isGreaterThan(1000);
        // 4 notes, last released at 1.9 s; the render is trimmed after the release tail and well below the 10 s cap
        assertThat(r.frames()).isBetween(48000L * 19 / 10, 48000L * 8);
        assertThat(Math.abs(s[s.length - 1]) + Math.abs(s[s.length - 2])).isLessThan(4 * 32);
    }

    @Test
    void renderingTwiceIsByteIdentical() throws Exception {
        assumeTrue(MidiRenderer.isAvailable(), "needs --add-exports java.desktop/com.sun.media.sound");
        MidiRenderer r = new MidiRenderer(null);
        byte[] a = r.render(arpeggio(4, 3)).pcm();
        byte[] b = r.render(arpeggio(4, 3)).pcm();
        assertThat(a).isEqualTo(b);
    }

    @Test
    void emptySequenceRendersNothing() throws Exception {
        assumeTrue(MidiRenderer.isAvailable(), "needs --add-exports java.desktop/com.sun.media.sound");
        assertThat(new MidiRenderer(null).render(new Sequence(Sequence.PPQ, 480)).pcm()).isEmpty();
    }

    /** Runs only with -PsoundFont=/path/to/GeneralUser-GS.sf2 (the file is not bundled in the repository). */
    @Test
    void rendersWithAnSf2FromAConfigurablePath() throws Exception {
        String prop = System.getProperty("dlna.test.soundfont", "");
        assumeTrue(!prop.isBlank() && Files.isRegularFile(Path.of(prop)), "no SoundFont configured");
        assumeTrue(MidiRenderer.isAvailable(), "needs --add-exports java.desktop/com.sun.media.sound");
        Path sf = Path.of(prop);
        MidiRenderer mr = new MidiRenderer(sf);
        for (int program : List.of(0, 24, 40, 56, 73)) {
            long t0 = System.nanoTime();
            MidiRenderer.Result r = mr.render(arpeggio(program, 4));
            double secs = (System.nanoTime() - t0) / 1e9;
            System.out.printf("[spike] %s program %d: rendered %.3f s of audio in %.2f s wall%n",
                    sf.getFileName(), program, r.frames() / 48000.0, secs);
            assertThat(r.frames()).isBetween(48000L * 19 / 10, 48000L * 12);
        }
        Path real = sf.resolveSibling("Bond.mid");
        if (Files.isRegularFile(real)) {
            long t0 = System.nanoTime();
            Sequence seq = javax.sound.midi.MidiSystem.getSequence(real.toFile());
            MidiRenderer.Result r = mr.render(seq);
            double secs = (System.nanoTime() - t0) / 1e9;
            byte[] again = mr.render(seq).pcm();
            System.out.printf("[spike] real file Bond.mid: sequence %.1f s, event end %.1f s, rendered %.1f s in %.1f s"
                            + " wall (%.0fx real time), deterministic=%b%n", r.sequenceFrames() / 48000.0,
                    r.eventEndFrame() / 48000.0, r.frames() / 48000.0, secs, (r.frames() / 48000.0) / secs,
                    java.util.Arrays.equals(again, r.pcm()));
            assertThat(again).isEqualTo(r.pcm());
        }
    }
}
