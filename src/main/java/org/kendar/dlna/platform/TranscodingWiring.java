package org.kendar.dlna.platform;

import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Logger;

import org.kendar.dlna.ingestion.CompositeTagReader;
import org.kendar.dlna.ingestion.JAudioTaggerReader;
import org.kendar.dlna.ingestion.AbcFile;
import org.kendar.dlna.ingestion.AbcTagReader;
import org.kendar.dlna.ingestion.MidiTagReader;
import org.kendar.dlna.ingestion.OpusTagReader;
import org.kendar.dlna.ingestion.TagReader;
import org.kendar.dlna.transcoding.MidiRenderer;
import org.kendar.dlna.transcoding.SilenceTrimmer;
import org.kendar.dlna.transcoding.Transcoder;

/**
 * Decides what the transcoding side can do on this installation (MIDI needs transcoding enabled, the
 * {@code --add-exports} JVM flag and a SoundFont) and builds the {@link Transcoder} and the tag reader that go
 * with it. MIDI files are listed as unsupported, with the reason, when it cannot be rendered.
 */
final class TranscodingWiring {

    /** Bump when the set of readable formats changes: forces one full scan to re-evaluate unsupported files. */
    private static final String CAPABILITY_VERSION = "v10";

    private static final Logger LOG = Logger.getLogger(TranscodingWiring.class.getName());

    final Transcoder transcoder;
    final boolean midiAvailable;
    final String midiStamp;
    private final String midiReason;

    private TranscodingWiring(Transcoder transcoder, boolean midiAvailable, String midiStamp, String midiReason) {
        this.transcoder = transcoder;
        this.midiAvailable = midiAvailable;
        this.midiStamp = midiStamp;
        this.midiReason = midiReason;
    }

    static TranscodingWiring create(AppConfig.Transcode tc, Path dataDir) {
        if (!tc.enabled()) {
            return new TranscodingWiring(new Transcoder(null), false, "", "transcoding is disabled");
        }
        if (!MidiRenderer.isAvailable()) {
            return new TranscodingWiring(new Transcoder(null), false, "", "the JVM was started without "
                    + "--add-exports java.desktop/com.sun.media.sound=ALL-UNNAMED (java -jar honours the manifest)");
        }
        Optional<SoundFonts.Located> sf = SoundFonts.locate(tc.soundFontPath(), dataDir);
        if (sf.isEmpty()) {
            return new TranscodingWiring(new Transcoder(null), false, "", "no SoundFont: set "
                    + "transcode.soundFontPath to an .sf2 file (for example GeneralUser GS)");
        }
        int rate = MidiRenderer.RATE;
        SilenceTrimmer.Policy policy = new SilenceTrimmer.Policy(tc.midiSilenceThreshold(), rate / 2,
                rate * tc.midiMaxTailSeconds(), rate / 20);
        MidiRenderer renderer = new MidiRenderer(sf.get().file(), policy, "linear");
        LOG.info("MIDI rendering enabled with SoundFont " + sf.get().file());
        return new TranscodingWiring(new Transcoder(renderer, TranscodingWiring::loadAbcTune), true, sf.get().stamp(), "");
    }

    /** The n-th tune (from 1) of an ABC file as MIDI. */
    private static javax.sound.midi.Sequence loadAbcTune(Path file, int tune)
            throws java.io.IOException, javax.sound.midi.InvalidMidiDataException {
        java.util.List<AbcFile.Tune> tunes = AbcFile.parse(file);
        if (tune < 1 || tune > tunes.size()) {
            throw new java.io.IOException("no tune " + tune + " in " + file.getFileName());
        }
        return tunes.get(tune - 1).sequence();
    }

    /** Tags and basic properties of all formats this installation can serve (no sample-count probing). */
    TagReader plainTagReader() {
        return new CompositeTagReader(new JAudioTaggerReader(), new OpusTagReader(),
                new MidiTagReader(midiAvailable, midiReason), new AbcTagReader(midiAvailable, midiReason));
    }

    /** The tag reader for all formats this installation can serve, with exact sample counts. */
    TagReader tagReader() {
        return new ProbingTagReader(plainTagReader(), new Transcoder(null));
    }

    /** Appended to the roots fingerprint of the persisted index. */
    String fingerprintSuffix() {
        return "|" + CAPABILITY_VERSION + (midiAvailable ? "+midi" : "");
    }

    String midiReason() {
        return midiReason;
    }
}
