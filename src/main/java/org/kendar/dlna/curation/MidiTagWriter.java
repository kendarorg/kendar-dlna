package org.kendar.dlna.curation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.kendar.dlna.catalog.Format;

/**
 * {@link TagWriter} for MIDI files: title, artist and year only (the other fields of the edit are ignored), by
 * patching the bytes of the file ({@link MidiPatcher}) and checking the result against the original
 * ({@link MidiVerifier}). It works on the temporary copy the caller made; when anything fails it throws and the caller
 * deletes the copy, so the original is never touched.
 */
public final class MidiTagWriter implements TagWriter {

    /** Produces the patched bytes; a seam so that tests can inject a faulty patcher. */
    @FunctionalInterface
    interface Patcher {
        byte[] patch(byte[] smf, TagEdit edit) throws TagWriteException;
    }

    private final Patcher patcher;

    public MidiTagWriter() {
        this(MidiPatcher::patch);
    }

    MidiTagWriter(Patcher patcher) {
        this.patcher = patcher;
    }

    @Override
    public void write(Path file, Format format, TagEdit edit) throws TagWriteException {
        if (format != Format.MIDI) {
            throw new TagWriteException(format + " is not a MIDI file", null);
        }
        TagEdit mine = edit.restrictedTo(Format.MIDI.editableFields());
        if (mine.isEmpty()) {
            return;
        }
        try {
            byte[] original = Files.readAllBytes(file);
            byte[] patched = patcher.patch(original, mine);
            Files.write(file, patched);
            MidiVerifier.verify(original, Files.readAllBytes(file), mine);
        } catch (IOException e) {
            throw new TagWriteException("Cannot write MIDI tags: " + e.getMessage(), e);
        }
    }
}
