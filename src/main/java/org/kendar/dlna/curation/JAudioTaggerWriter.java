package org.kendar.dlna.curation;

import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.id3.ID3v24Tag;
import org.jaudiotagger.tag.wav.WavTag;
import org.kendar.dlna.catalog.Format;

/** {@link TagWriter} backed by jaudiotagger (MP3, OGG Vorbis, FLAC, WAV, AIFF). */
public final class JAudioTaggerWriter implements TagWriter {

    static {
        Logger.getLogger("org.jaudiotagger").setLevel(Level.OFF);
    }

    @Override
    public void write(Path file, Format format, TagEdit edit) throws TagWriteException {
        if (!format.tagsEditable()) {
            throw new TagWriteException(format + " tags cannot be edited", null);
        }
        try {
            AudioFile af = AudioFileIO.read(file.toFile());
            Tag tag = af.getTagOrCreateAndSetDefault();
            if (tag instanceof WavTag wav) {
                // a WAV holds an ID3 chunk and a LIST INFO chunk; the readers use the ID3 one
                if (wav.getID3Tag() == null) {
                    wav.setID3Tag(new ID3v24Tag());
                }
                tag = wav.getID3Tag();
            }
            apply(tag, FieldKey.TITLE, edit.title());
            apply(tag, FieldKey.ARTIST, edit.artist());
            apply(tag, FieldKey.ALBUM, edit.album());
            apply(tag, FieldKey.ALBUM_ARTIST, edit.albumArtist());
            apply(tag, FieldKey.GENRE, edit.genre());
            apply(tag, FieldKey.COMPOSER, edit.composer());
            apply(tag, FieldKey.YEAR, edit.year());
            apply(tag, FieldKey.TRACK, edit.trackNo());
            apply(tag, FieldKey.DISC_NO, edit.discNo());
            apply(tag, FieldKey.DISC_TOTAL, edit.discTotal());
            af.commit();
        } catch (Exception e) {
            throw new TagWriteException("Cannot write tags: " + e.getMessage(), e);
        }
    }

    private static void apply(Tag tag, FieldKey key, String value) throws Exception {
        if (value == null) {
            return;
        }
        if (value.isEmpty()) {
            tag.deleteField(key);
        } else {
            tag.setField(key, value);
        }
    }
}
