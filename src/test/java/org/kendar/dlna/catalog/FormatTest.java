package org.kendar.dlna.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FormatTest {

    @Test
    void m4aAndMp4MapToAacAndRawAacToAdtsWhileAlacHasNoExtension() {
        assertThat(Format.fromFileName("a.m4a")).contains(Format.AAC);
        assertThat(Format.fromFileName("A.M4A")).contains(Format.AAC);
        assertThat(Format.fromFileName("a.mp4")).contains(Format.AAC);
        assertThat(Format.fromFileName("a.aac")).contains(Format.ADTS);
        assertThat(Format.ALAC.extensions()).isEmpty();
        assertThat(Format.fromFileName("a.alac")).isEmpty();
    }

    @Test
    void dsdAndAudiobooksAreNotFormats() {
        assertThat(Format.fromFileName("a.dsf")).isEmpty();
        assertThat(Format.fromFileName("a.dff")).isEmpty();
        assertThat(Format.fromFileName("a.m4b")).isEmpty();
    }

    @Test
    void tagsAreEditableForMp4AudioButNotForRawAdts() {
        assertThat(Format.AAC.tagsEditable()).isTrue();
        assertThat(Format.ALAC.tagsEditable()).isTrue();
        assertThat(Format.ADTS.tagsEditable()).isFalse();
        assertThat(Format.OPUS.tagsEditable()).isFalse();
        assertThat(Format.OPUS.editableFields()).isEmpty();
        assertThat(Format.MIDI.tagsEditable()).isTrue();
        assertThat(Format.MIDI.editableFields()).containsExactly("title", "artist", "year");
        assertThat(Format.FLAC.editableFields()).hasSize(10).contains("album", "composer");
    }

    @Test
    void aacAndAlacAreTheSameKindOfFileButNothingElseIs() {
        assertThat(Format.sameFileKind(Format.AAC, Format.ALAC)).isTrue();
        assertThat(Format.sameFileKind(Format.ALAC, Format.AAC)).isTrue();
        assertThat(Format.sameFileKind(Format.MP3, Format.MP3)).isTrue();
        assertThat(Format.sameFileKind(Format.AAC, Format.ADTS)).isFalse();
        assertThat(Format.sameFileKind(Format.FLAC, Format.ALAC)).isFalse();
    }
}
