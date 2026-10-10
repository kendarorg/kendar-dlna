package org.kendar.dlna.curation;

import java.nio.file.Path;

import org.kendar.dlna.catalog.Format;

/** {@link TagWriter} that picks the writer by format: MIDI gets the byte patcher, everything else jaudiotagger. */
public final class PerFormatTagWriter implements TagWriter {

    private final TagWriter midi;
    private final TagWriter general;

    public PerFormatTagWriter() {
        this(new MidiTagWriter(), new JAudioTaggerWriter());
    }

    public PerFormatTagWriter(TagWriter midi, TagWriter general) {
        this.midi = midi;
        this.general = general;
    }

    @Override
    public void write(Path file, Format format, TagEdit edit) throws TagWriteException {
        (format == Format.MIDI ? midi : general).write(file, format, edit);
    }
}
