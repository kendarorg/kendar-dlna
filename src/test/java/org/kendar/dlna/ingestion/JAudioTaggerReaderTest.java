package org.kendar.dlna.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.Format;

class JAudioTaggerReaderTest {

    private final JAudioTaggerReader reader = new JAudioTaggerReader();

    @TempDir
    Path dir;

    @Test
    void supportsExactlyTheImplementedFormats() {
        assertThat(reader.supports(Format.MP3)).isTrue();
        assertThat(reader.supports(Format.OGG)).isTrue();
        assertThat(reader.supports(Format.FLAC)).isTrue();
        assertThat(reader.supports(Format.WAV)).isTrue();
        assertThat(reader.supports(Format.AIFF)).isTrue();
        assertThat(reader.supports(Format.AAC)).isTrue();
        assertThat(reader.supports(Format.ALAC)).isTrue();
        assertThat(reader.supports(Format.ADTS)).isTrue();
        assertThat(reader.supports(Format.MIDI)).isFalse();
        assertThat(reader.supports(Format.OPUS)).isFalse();
    }

    @Test
    void readsDurationAndFormatOfGeneratedWav() throws Exception {
        Path wav = dir.resolve("tone.wav");
        writeSilence(wav, AudioFileFormat.Type.WAVE, 2.0f);

        TrackMeta m = reader.read(wav, Format.WAV);

        assertThat(m.durationMillis()).isBetween(1990L, 2010L);
        assertThat(m.sampleRate()).isEqualTo(8000);
        assertThat(m.channels()).isEqualTo(1);
        assertThat(m.embeddedArt()).isFalse();
        assertThat(m.tags()).isNotNull();
    }

    @Test
    void readsGeneratedAiff() throws Exception {
        Path aiff = dir.resolve("tone.aiff");
        writeSilence(aiff, AudioFileFormat.Type.AIFF, 1.0f);

        TrackMeta m = reader.read(aiff, Format.AIFF);

        assertThat(m.durationMillis()).isBetween(990L, 1010L);
    }

    @Test
    void garbageFileFailsWithTagReadException() throws Exception {
        Path bad = dir.resolve("bad.mp3");
        Files.writeString(bad, "this is not audio");
        assertThatThrownBy(() -> reader.read(bad, Format.MP3)).isInstanceOf(TagReader.TagReadException.class);
    }

@Test
void readsMultiValueArtistsAndGenresFromId3v24() throws Exception {
    Path wav = dir.resolve("multi.wav");
    writeSilence(wav, AudioFileFormat.Type.WAVE, 1.0f);
    org.jaudiotagger.audio.AudioFile af = org.jaudiotagger.audio.AudioFileIO.read(wav.toFile());
    org.jaudiotagger.tag.Tag tag = af.getTagOrCreateAndSetDefault();
    tag.setField(org.jaudiotagger.tag.FieldKey.TITLE, "Duet");
    tag.setField(org.jaudiotagger.tag.FieldKey.ARTIST, "Alice");
    tag.addField(org.jaudiotagger.tag.FieldKey.ARTIST, "Bob");
    tag.setField(org.jaudiotagger.tag.FieldKey.ALBUM, "Together");
    tag.setField(org.jaudiotagger.tag.FieldKey.GENRE, "Pop");
    tag.setField(org.jaudiotagger.tag.FieldKey.COMPOSER, "Carl");
    tag.addField(org.jaudiotagger.tag.FieldKey.COMPOSER, "Dora");
    af.commit();

    var tags = reader.read(wav, Format.WAV).tags();

    assertThat(tags.title()).isEqualTo("Duet");
    assertThat(tags.album()).isEqualTo("Together");
    assertThat(tags.allArtists()).containsExactly("Alice", "Bob");
    assertThat(tags.allGenres()).containsExactly("Pop");
    assertThat(tags.composer()).isEqualTo("Carl");
    assertThat(tags.allComposers()).containsExactly("Carl", "Dora");
}

@Test
void leadingIntParsesTrackStyleValues() {
        assertThat(JAudioTaggerReader.leadingInt("7/12")).isEqualTo(7);
        assertThat(JAudioTaggerReader.leadingInt("2024-05-01")).isEqualTo(2024);
        assertThat(JAudioTaggerReader.leadingInt("abc")).isNull();
        assertThat(JAudioTaggerReader.leadingInt(null)).isNull();
    }

    private static void writeSilence(Path target, AudioFileFormat.Type type, float seconds) throws Exception {
        AudioFormat fmt = new AudioFormat(8000f, 16, 1, true, type == AudioFileFormat.Type.AIFF);
        int frames = (int) (8000 * seconds);
        byte[] pcm = new byte[frames * 2];
        try (AudioInputStream in = new AudioInputStream(new ByteArrayInputStream(pcm), fmt, frames)) {
            AudioSystem.write(in, type, target.toFile());
        }
    }
}
