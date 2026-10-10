package org.kendar.dlna.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.jaudiotagger.tag.images.ArtworkFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.Format;

class EmbeddedArtExtractorTest {

    @TempDir
    Path dir;

    private Path wav(String name) throws Exception {
        Path p = dir.resolve(name);
        AudioFormat fmt = new AudioFormat(8000f, 16, 1, true, false);
        byte[] pcm = new byte[16000];
        try (AudioInputStream in = new AudioInputStream(new ByteArrayInputStream(pcm), fmt, 8000)) {
            AudioSystem.write(in, AudioFileFormat.Type.WAVE, p.toFile());
        }
        return p;
    }

    @Test
    void fileWithoutArtOrMissingFileYieldsEmpty() throws Exception {
        EmbeddedArtExtractor x = new EmbeddedArtExtractor();
        assertThat(x.extract(wav("plain.wav"))).isEmpty();
        assertThat(x.extract(dir.resolve("missing.mp3"))).isEmpty();
        Path junk = dir.resolve("junk.mp3");
        Files.writeString(junk, "junk");
        assertThat(x.extract(junk)).isEmpty();
    }

    @Test
    void extractsArtWrittenIntoTheTagAndReaderFlagsIt() throws Exception {
        Path p = wav("art.wav");
        BufferedImage img = new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", b);
        byte[] jpg = b.toByteArray();
        AudioFile af = AudioFileIO.read(p.toFile());
        Tag tag = af.getTagOrCreateAndSetDefault();
        Artwork art = ArtworkFactory.getNew();
        art.setBinaryData(jpg);
        art.setMimeType("image/jpeg");
        tag.setField(art);
        af.commit();

        assertThat(new EmbeddedArtExtractor().extract(p)).hasValueSatisfying(d -> assertThat(d).isEqualTo(jpg));
        assertThat(new JAudioTaggerReader().read(p, Format.WAV).embeddedArt()).isTrue();
    }
}
