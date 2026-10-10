package org.kendar.dlna.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.Track;

import org.junit.jupiter.api.Test;
import org.kendar.dlna.catalog.TrackTags;

class MidiTagReaderTest {

    private static void meta(Track track, int type, String text, long tick) throws Exception {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        track.add(new MidiEvent(new MetaMessage(type, data, data.length), tick));
    }

    @Test
    void titleIsTheFirstTrackNameNotAnInstrumentName() throws Exception {
        Sequence seq = new Sequence(Sequence.PPQ, 480, 2);
        meta(seq.getTracks()[0], 0x03, "Moonlight Sonata", 0);
        meta(seq.getTracks()[1], 0x03, "Acoustic Grand Piano", 0);

        TrackTags tags = MidiTagReader.tags(seq);
        assertThat(tags.title()).isEqualTo("Moonlight Sonata");
        assertThat(tags.artist()).isNull();
        assertThat(tags.album()).isNull();
        assertThat(tags.genre()).isNull();
    }

    @Test
    void instrumentNameOfALaterTrackIsNeverTheTitle() throws Exception {
        Sequence seq = new Sequence(Sequence.PPQ, 480, 2);
        meta(seq.getTracks()[1], 0x03, "Violin", 0);

        assertThat(MidiTagReader.tags(seq).title()).isNull();
    }

    @Test
    void karaokeTextLinesGiveTitleAndArtist() throws Exception {
        Sequence seq = new Sequence(Sequence.PPQ, 480, 2);
        meta(seq.getTracks()[0], 0x01, "@KMIDI KARAOKE FILE", 0);
        meta(seq.getTracks()[0], 0x01, "@LENGL", 0);
        meta(seq.getTracks()[0], 0x01, "@TYesterday", 0);
        meta(seq.getTracks()[0], 0x01, "@TThe Beatles", 0);
        meta(seq.getTracks()[0], 0x01, "@TTranscribed by someone", 0);
        meta(seq.getTracks()[1], 0x01, "/Yes-ter-day", 10);   // lyric text without the @T prefix

        TrackTags tags = MidiTagReader.tags(seq);
        assertThat(tags.title()).isEqualTo("Yesterday");
        assertThat(tags.artist()).isEqualTo("The Beatles");
    }

    @Test
    void sequenceNameWinsOverTheKaraokeTitleButArtistStillComesFromTheSecondLine() throws Exception {
        Sequence seq = new Sequence(Sequence.PPQ, 480, 1);
        meta(seq.getTracks()[0], 0x03, "Yesterday (remix)", 0);
        meta(seq.getTracks()[0], 0x01, "@TYesterday", 0);
        meta(seq.getTracks()[0], 0x01, "@TThe Beatles", 0);

        TrackTags tags = MidiTagReader.tags(seq);
        assertThat(tags.title()).isEqualTo("Yesterday (remix)");
        assertThat(tags.artist()).isEqualTo("The Beatles");
    }

    @Test
    void yearComesFromTheFirstCopyrightNotice() throws Exception {
        Sequence seq = new Sequence(Sequence.PPQ, 480, 1);
        meta(seq.getTracks()[0], 0x02, "(c) 1996 Some Studio", 0);
        meta(seq.getTracks()[0], 0x02, "(c) 2005 Other", 0);

        assertThat(MidiTagReader.tags(seq).year()).isEqualTo(1996);
    }

    @Test
    void copyrightWithoutAYearGivesNoYearAndLongNumbersAreNotYears() throws Exception {
        Sequence seq = new Sequence(Sequence.PPQ, 480, 1);
        meta(seq.getTracks()[0], 0x02, "All rights reserved, id 119960", 0);

        assertThat(MidiTagReader.tags(seq).year()).isNull();
    }

    @Test
    void emptySequenceGivesEmptyTags() throws Exception {
        TrackTags tags = MidiTagReader.tags(new Sequence(Sequence.PPQ, 480, 1));
        assertThat(tags.title()).isNull();
        assertThat(tags.artist()).isNull();
        assertThat(tags.year()).isNull();
    }
}
