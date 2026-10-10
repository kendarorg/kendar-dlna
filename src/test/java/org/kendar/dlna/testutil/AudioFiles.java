package org.kendar.dlna.testutil;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/** Generators of small real audio files for pipeline tests. */
public final class AudioFiles {

    private AudioFiles() {
    }

    /** A 16-bit WAV with a sine; {@code channels} identical channels. */
    public static Path sineWav(Path file, int rate, int channels, int frames, double freq) {
        byte[] data = new byte[frames * channels * 2];
        for (int i = 0; i < frames; i++) {
            short s = (short) Math.round(0.5 * 32767 * Math.sin(2 * Math.PI * freq * i / rate));
            for (int c = 0; c < channels; c++) {
                int p = (i * channels + c) * 2;
                data[p] = (byte) s;
                data[p + 1] = (byte) (s >> 8);
            }
        }
        AudioFormat f = new AudioFormat(rate, 16, channels, true, false);
        try (AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(data), f, frames)) {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }

    /** One of the fixtures in src/test/resources/audio copied next to the test's files. */
    public static Path fixture(String name, Path target) {
        try (var in = AudioFiles.class.getResourceAsStream("/audio/" + name)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A one-track MIDI file: a few arpeggio notes (about {@code seconds} long at 120 bpm), titled. */
    public static Path midi(Path file, String title, int seconds) {
        try {
            Sequence seq = new Sequence(Sequence.PPQ, 480);
            javax.sound.midi.Track t = seq.createTrack();
            if (title != null) {
                byte[] b = title.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                t.add(new MidiEvent(new MetaMessage(0x03, b, b.length), 0));
            }
            int[] notes = {60, 64, 67, 72};
            long tick = 0;
            for (int i = 0; i < seconds * 2; i++) {
                int n = notes[i % notes.length];
                t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, n, 100), tick));
                t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, n, 0), tick + 400));
                tick += 480;
            }
            MidiSystem.write(seq, 1, file.toFile());
            return file;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
