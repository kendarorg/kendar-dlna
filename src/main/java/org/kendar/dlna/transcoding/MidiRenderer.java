package org.kendar.dlna.transcoding;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequence;
import javax.sound.midi.Soundbank;
import javax.sound.midi.Synthesizer;
import javax.sound.midi.Track;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;

/**
 * Offline (no audio device) MIDI rendering through the JDK software synthesizer (Gervill).
 *
 * <p>{@code AudioSynthesizer.openStream} lives in {@code com.sun.media.sound}, which the JDK does not export, so it is
 * reached through reflection and the JVM must run with {@code --add-exports java.desktop/com.sun.media.sound=ALL-UNNAMED}
 * (the jar manifest attribute {@code Add-Exports} does the same for {@code java -jar}). {@link #isAvailable()} reports
 * whether that works. Renders 48 kHz, 16-bit, stereo.
 */
public final class MidiRenderer {

    /** Rendered audio. {@code pcm} is interleaved s16le stereo at {@code sampleRate}, already tail-trimmed. */
    public record Result(byte[] pcm, int sampleRate, long sequenceFrames, long eventEndFrame, long frames) {
    }

    public static final int RATE = 48_000;
    private static final AudioFormat FORMAT = new AudioFormat(RATE, 16, 2, true, false);

    private final Path soundFont;
    private final SilenceTrimmer.Policy policy;
    private final String interpolation;
    private Soundbank bank;

    /** @param soundFont SF2 file, or null for Gervill's built-in emergency soundbank */
    public MidiRenderer(Path soundFont, SilenceTrimmer.Policy policy, String interpolation) {
        this.soundFont = soundFont;
        this.policy = policy;
        this.interpolation = interpolation;
    }

    public MidiRenderer(Path soundFont) {
        this(soundFont, SilenceTrimmer.Policy.forRate(RATE), "linear");
    }

    /** True when Gervill can be driven in offline mode on this JVM (needs the Add-Exports described above). */
    public static boolean isAvailable() {
        try {
            Synthesizer s = newSynth();
            return s != null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private static Synthesizer newSynth() throws ReflectiveOperationException {
        Class<?> c = Class.forName("com.sun.media.sound.SoftSynthesizer");
        return (Synthesizer) c.getDeclaredConstructor().newInstance();
    }

    public Result render(Sequence sequence) throws IOException, InvalidMidiDataException, MidiUnavailableException {
        List<TimedEvent> events = timeline(sequence);
        long lastMicros = events.isEmpty() ? 0 : events.get(events.size() - 1).micros();
        long eventEndFrame = lastMicros * RATE / 1_000_000L;
        long sequenceFrames = sequence.getMicrosecondLength() * RATE / 1_000_000L;
        Synthesizer synth = null;
        try {
            synth = newSynth();
            Class<?> audioSynth = Class.forName("com.sun.media.sound.AudioSynthesizer");
            Method openStream = audioSynth.getMethod("openStream", AudioFormat.class, Map.class);
            Map<String, Object> info = new HashMap<>();
            info.put("interpolation", interpolation);
            info.put("max polyphony", 128);
            AudioInputStream stream = (AudioInputStream) openStream.invoke(synth, FORMAT, info);
            if (soundFont != null) {
                Soundbank bank = bank();
                synth.unloadAllInstruments(synth.getDefaultSoundbank());
                synth.loadAllInstruments(bank);
            }
            Receiver rx = synth.getReceiver();
            for (TimedEvent e : events) {
                rx.send(e.message(), e.micros());
            }
            byte[] pcm;
            try (InputStream in = stream) {
                pcm = SilenceTrimmer.readTrimmed(in, 2, eventEndFrame, policy);
            }
            return new Result(pcm, RATE, sequenceFrames, eventEndFrame, pcm.length / 4);
        } catch (ReflectiveOperationException e) {
            throw new IOException("Gervill offline mode unavailable (missing --add-exports "
                    + "java.desktop/com.sun.media.sound=ALL-UNNAMED?): " + e, e);
        } finally {
            if (synth != null) {
                synth.close();
            }
        }
    }

    /** The parsed SF2 is immutable and shared by every synthesizer; parsing it (32 MB) dominates a short render. */
    private synchronized Soundbank bank() throws IOException, InvalidMidiDataException {
        if (bank == null) {
            bank = MidiSystem.getSoundbank(soundFont.toFile());
        }
        return bank;
    }

    record TimedEvent(long micros, MidiMessage message) {
    }

    /** Merges all tracks, applying tempo changes (PPQ) or SMPTE timing, into microsecond-stamped events. */
    static List<TimedEvent> timeline(Sequence seq) {
        List<MidiEvent> all = new ArrayList<>();
        for (Track t : seq.getTracks()) {
            for (int i = 0; i < t.size(); i++) {
                all.add(t.get(i));
            }
        }
        all.sort((a, b) -> Long.compare(a.getTick(), b.getTick()));
        List<TimedEvent> out = new ArrayList<>(all.size());
        boolean ppq = seq.getDivisionType() == Sequence.PPQ;
        int res = seq.getResolution();
        double tempo = 500_000; // microseconds per quarter note
        long lastTick = 0;
        double micros = 0;
        for (MidiEvent e : all) {
            long tick = e.getTick();
            if (ppq) {
                micros += (tick - lastTick) * tempo / res;
            } else {
                micros += (tick - lastTick) * 1_000_000.0 / (seq.getDivisionType() * res);
            }
            lastTick = tick;
            MidiMessage m = e.getMessage();
            if (m instanceof MetaMessage mm) {
                if (mm.getType() == 0x51 && mm.getData().length == 3) {
                    byte[] d = mm.getData();
                    tempo = (d[0] & 0xff) << 16 | (d[1] & 0xff) << 8 | (d[2] & 0xff);
                }
                continue; // meta messages are not for the synthesizer
            }
            out.add(new TimedEvent(Math.round(micros), m));
        }
        return out;
    }
}
