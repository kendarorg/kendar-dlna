package org.kendar.dlna.it;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.imageio.ImageIO;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;

/**
 * Generates the test library on the local disk (WAV through Java Sound, MIDI through javax.sound.midi, the small
 * fixtures of {@code src/test/resources/audio}, settings.json files, cover art, a playlist). The result is copied
 * into the container (no bind mounts: the Docker engine may be remote).
 */
final class LibraryFactory {

    /** The generated tree. {@code music} is the root named {@value #ROOT}. */
    record Library(Path music, Path playlists, int trackCount, int unsupportedCount) {
    }

    static final String ROOT = "Music";
    static final String PLAYLIST_KEY = "fixture";
    static final String PLAYLIST_NAME = "Fixture List";
    static final int MANY = 120;

    private int tracks;
    private int unsupported;
    private final Path music;
    private final Path fixtures;

    private LibraryFactory(Path base) {
        this.music = base.resolve("music");
        this.fixtures = ItEnv.projectDir().resolve("src/test/resources/audio");
    }

    static Library generate(Path base) throws Exception {
        Logger.getLogger("org.jaudiotagger").setLevel(Level.OFF);
        LibraryFactory f = new LibraryFactory(base);
        return f.build(base.resolve("playlists"));
    }

    private Library build(Path playlists) throws Exception {
        Files.createDirectories(music);
        Files.createDirectories(playlists);
        text(music.resolve("settings.json"), "{\"random\":4,\"views\":[\"album\",\"artist\",\"genre\",\"title\"]}");
        Files.write(music.resolve("front.jpg"), cover(new Color(180, 40, 40)));

        Path alpha = Files.createDirectories(music.resolve("Alpha Album"));
        text(alpha.resolve("settings.json"), "{\"sort\":\"track\",\"random\":3,\"views\":[\"album\",\"artist\"],"
                + "\"album\":{\"title\":\"Alpha Greatest\",\"albumArtist\":\"The Testers\",\"year\":2001,"
                + "\"genre\":\"Rock\"}}");
        Files.write(alpha.resolve("front.jpg"), cover(new Color(40, 120, 200)));
        wav(alpha.resolve("01 Alpha One.wav"), 22050, 0.4, 440);
        fixture("sweep.flac", alpha.resolve("02 Alpha Two.flac"));
        tag(alpha.resolve("02 Alpha Two.flac"), "Alpha Two", "The Testers", "Alpha Greatest", "Rock", "2");
        fixture("sweep.ogg", alpha.resolve("03 Alpha Three.ogg"));
        tag(alpha.resolve("03 Alpha Three.ogg"), "Alpha Three", "The Testers", "Alpha Greatest", "Rock", "3");
        fixture("sweep.mp3", alpha.resolve("04 Alpha Four.mp3"));
        tag(alpha.resolve("04 Alpha Four.mp3"), "Alpha Four", "The Testers", "Alpha Greatest", "Rock", "4");

        Path beta = Files.createDirectories(music.resolve("Beta Album"));
        text(beta.resolve("settings.json"), "{\"sort\":\"title\"}");
        fixture("sweep.opus", beta.resolve("Beta Opus.opus"));
        fixture("sweep.aiff", beta.resolve("Beta Aiff.aiff"));
        wav(beta.resolve("Beta Wave.wav"), 44100, 0.3, 660);
        midi(beta.resolve("Beta Midi.mid"));
        fixture("sweep.mp3", beta.resolve("Beta Tagged.mp3"));
        tag(beta.resolve("Beta Tagged.mp3"), "Beta Tagged", "Other Band", "Beta Sessions", "Jazz", "1");

        Path mixed = Files.createDirectories(music.resolve("Mixed"));
        text(mixed.resolve("settings.json"), "{\"sort\":\"explicit\",\"order\":[\"Zed.wav\",\"Deep\",\"Aaa.wav\"],"
                + "\"random\":2}");
        wav(mixed.resolve("Aaa.wav"), 22050, 0.2, 300);
        wav(mixed.resolve("Zed.wav"), 22050, 0.2, 350);
        Path deeper = Files.createDirectories(mixed.resolve("Deep").resolve("Deeper"));
        wav(mixed.resolve("Deep").resolve("Deep One.wav"), 22050, 0.2, 500);
        wav(deeper.resolve("Deeper Still.wav"), 22050, 0.2, 520);

        Path many = Files.createDirectories(music.resolve("Many"));
        text(many.resolve("settings.json"), "{\"sort\":\"filename\"}");
        for (int i = 1; i <= MANY; i++) {
            wav(many.resolve(String.format("Track %03d.wav", i)), 8000, 0.1, 200 + i * 3);
        }

        Path uni = Files.createDirectories(music.resolve("Ünïcode ñ"));
        wav(uni.resolve("Tëst – ü.wav"), 22050, 0.2, 700);

        Path misc = Files.createDirectories(music.resolve("Misc"));
        Files.write(misc.resolve("song.m4a"), new byte[] { 0, 0, 0, 24, 'f', 't', 'y', 'p' });
        unsupported++;
        text(misc.resolve("notes.txt"), "not music");

        text(playlists.resolve(PLAYLIST_KEY + ".json"), "{\"name\":\"" + PLAYLIST_NAME + "\",\"songs\":["
                + "\"Music/Alpha Album/01 Alpha One.wav\",\"Music/Beta Album/Beta Tagged.mp3\","
                + "\"Music/Mixed/Zed.wav\",\"Music/Gone/missing.mp3\"]}");
        return new Library(music, playlists, tracks, unsupported);
    }

    private void fixture(String name, Path target) throws IOException {
        Files.copy(fixtures.resolve(name), target);
        tracks++;
    }

    private static void text(Path p, String s) throws IOException {
        Files.writeString(p, s, StandardCharsets.UTF_8);
    }

    /** Best effort: a file the tag writer cannot handle simply keeps no tags (its title is then the file name). */
    private static void tag(Path file, String title, String artist, String album, String genre, String track) {
        try {
            AudioFile af = AudioFileIO.read(file.toFile());
            Tag t = af.getTagOrCreateAndSetDefault();
            t.setField(FieldKey.TITLE, title);
            t.setField(FieldKey.ARTIST, artist);
            t.setField(FieldKey.ALBUM, album);
            t.setField(FieldKey.GENRE, genre);
            t.setField(FieldKey.TRACK, track);
            af.commit();
        } catch (Exception e) {
            System.err.println("[it] cannot tag " + file.getFileName() + ": " + e);
        }
    }

    private void wav(Path file, int rate, double seconds, double hz) throws IOException {
        sineWav(file, rate, seconds, hz);
        tracks++;
    }

    /** A mono 16 bit WAV with a sine tone. */
    static void sineWav(Path file, int rate, double seconds, double hz) throws IOException {
        int frames = (int) (rate * seconds);
        byte[] pcm = new byte[frames * 2];
        for (int i = 0; i < frames; i++) {
            short s = (short) (Math.sin(2 * Math.PI * hz * i / rate) * 12000);
            pcm[2 * i] = (byte) s;
            pcm[2 * i + 1] = (byte) (s >> 8);
        }
        AudioFormat fmt = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16, 1, 2, rate, false);
        try (AudioInputStream in = new AudioInputStream(new ByteArrayInputStream(pcm), fmt, frames)) {
            AudioSystem.write(in, AudioFileFormat.Type.WAVE, file.toFile());
        }
    }

    private void midi(Path file) throws Exception {
        Sequence seq = new Sequence(Sequence.PPQ, 480);
        Track t = seq.createTrack();
        int[] notes = { 60, 64, 67, 72 };
        for (int i = 0; i < notes.length; i++) {
            t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, notes[i], 90), i * 240L));
            t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, notes[i], 0), i * 240L + 220));
        }
        MidiSystem.write(seq, 1, file.toFile());
        tracks++;
    }

    static byte[] cover(Color color) throws IOException {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, 64, 64);
        g.setColor(Color.WHITE);
        g.fillOval(16, 16, 32, 32);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }
}
