package org.kendar.dlna.testutil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** A 10 second WAV (44.1 kHz stereo) described by a three track cue sheet, written into a directory. */
public final class CueFixtures {

    public static final int RATE = 44100;
    public static final int FRAMES = 441_000;
    /** Start samples of the three tracks of {@link #CUE}: 0, 3 s + 37 frames, 6 s. */
    public static final long[] STARTS = {0, 154_056, 264_600};

    public static final String CUE = """
            REM GENRE "Rock"
            REM DATE 1999
            PERFORMER "The Band"
            TITLE "Live Album"
            FILE "disc.wav" WAVE
              TRACK 01 AUDIO
                TITLE "Opening"
                PERFORMER "The Band"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "Middle"
                PERFORMER "Guest"
                INDEX 00 00:03:00
                INDEX 01 00:03:37
              TRACK 03 AUDIO
                TITLE "Finale"
                INDEX 01 00:06:00
            """;

    private CueFixtures() {
    }

    /** Writes {@code disc.wav} and {@code disc.cue} (the default sheet) into {@code dir}; returns the wav. */
    public static Path write(Path dir) {
        return write(dir, CUE);
    }

    public static Path write(Path dir, String cue) {
        try {
            Files.createDirectories(dir);
            Path wav = AudioFiles.sineWav(dir.resolve("disc.wav"), RATE, 2, FRAMES, 440);
            Files.writeString(dir.resolve("disc.cue"), cue, StandardCharsets.UTF_8);
            return wav;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
