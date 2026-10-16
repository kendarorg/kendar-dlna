package org.kendar.dlna.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.flac.FlacTag;
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTagField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.testutil.AudioFiles;

class ExtraTagsTest {

    @TempDir
    Path tmp;

    private Path flacWith(String name, String... fieldsAndValues) throws Exception {
        Path f = tmp.resolve(name);
        AudioFiles.fixture("sweep.flac", f);
        AudioFile af = AudioFileIO.read(f.toFile());
        FlacTag tag = (FlacTag) af.getTagOrCreateAndSetDefault();
        for (int i = 0; i < fieldsAndValues.length; i += 2) {
            tag.getVorbisCommentTag().addField(new VorbisCommentTagField(fieldsAndValues[i], fieldsAndValues[i + 1]));
        }
        af.commit();
        return f;
    }

    @Test
    void onlyTheListedExtraTagsAreKeptAndEveryCustomTagIsDiscovered() throws Exception {
        Path song = flacWith("a.flac", "MOOD", "calm", "TEMPO", "slow", "ARTIST", "Ann");
        TagDiscovery found = new TagDiscovery();
        JAudioTaggerReader reader = new JAudioTaggerReader(() -> List.of("Mood"), found);

        TrackMeta meta = reader.read(song, Format.FLAC);

        assertThat(meta.tags().extra("mood")).containsExactly("calm");
        assertThat(meta.tags().extra("MOOD")).as("names are case-insensitive").containsExactly("calm");
        assertThat(meta.tags().extra("tempo")).as("not listed: not kept").isEmpty();
        assertThat(found.counts()).containsOnlyKeys("mood", "tempo");   // ARTIST is a standard tag
    }

    @Test
    void withoutAnExtraTagListNothingIsKeptButTheTagsAreStillFound() throws Exception {
        Path song = flacWith("b.flac", "MOOD", "calm");
        TagDiscovery found = new TagDiscovery();
        TrackMeta meta = new JAudioTaggerReader(() -> List.of(), found).read(song, Format.FLAC);

        assertThat(meta.tags().extras()).isEmpty();
        assertThat(found.counts()).containsEntry("mood", 1);
    }

    @Test
    void countsCountFilesAndTheFileSurvivesARestart() throws Exception {
        TagDiscovery found = new TagDiscovery();
        JAudioTaggerReader reader = new JAudioTaggerReader(() -> List.of(), found);
        reader.read(flacWith("c1.flac", "MOOD", "calm"), Format.FLAC);
        reader.read(flacWith("c2.flac", "MOOD", "loud", "MOOD", "wild"), Format.FLAC);
        assertThat(found.counts()).containsEntry("mood", 2);   // two files, not three values

        Path file = tmp.resolve("cfg/tags.json");
        found.write(file);
        assertThat(file).exists();
        Map<String, Integer> loaded = TagDiscovery.load(file);
        assertThat(loaded).containsEntry("mood", 2);

        TagDiscovery again = new TagDiscovery();
        again.mergeKnown(loaded);
        again.seen(List.of("tempo"));
        assertThat(again.counts()).containsEntry("mood", 2).containsEntry("tempo", 1);
        again.reset();
        assertThat(again.counts()).isEmpty();
        assertThat(TagDiscovery.load(tmp.resolve("missing.json"))).isEmpty();
    }

    @Test
    void extrasSurviveInTheCatalogJson() throws IOException {
        org.kendar.dlna.catalog.TrackTags tags = new org.kendar.dlna.catalog.TrackTags("T", null, null, null, null,
                null, null, null, null).withExtras(Map.of("Mood", List.of("calm", "slow")));
        String json = org.kendar.dlna.shared.Json.mapper().writeValueAsString(tags);
        org.kendar.dlna.catalog.TrackTags back = org.kendar.dlna.shared.Json.mapper().readValue(json,
                org.kendar.dlna.catalog.TrackTags.class);
        assertThat(back.extra("mood")).containsExactly("calm", "slow");
        assertThat(back).isEqualTo(tags);
        // an old catalog without the field still loads
        org.kendar.dlna.catalog.TrackTags old = org.kendar.dlna.shared.Json.mapper()
                .readValue("{\"title\":\"X\"}", org.kendar.dlna.catalog.TrackTags.class);
        assertThat(old.extras()).isEmpty();
        assertThat(Files.exists(tmp)).isTrue();
    }
}
