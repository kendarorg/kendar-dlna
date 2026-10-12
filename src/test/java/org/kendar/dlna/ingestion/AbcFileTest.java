package org.kendar.dlna.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

import org.junit.jupiter.api.Test;

class AbcFileTest {

    private static List<Integer> pitches(AbcFile.Tune t) throws Exception {
        Sequence seq = t.sequence();
        List<Integer> out = new ArrayList<>();
        for (Track track : seq.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent e = track.get(i);
                if (e.getMessage() instanceof ShortMessage m && m.getCommand() == ShortMessage.NOTE_ON) {
                    out.add(m.getData1());
                }
            }
        }
        return out;
    }

    @Test
    void readsHeaderFieldsAndTheExactLength() throws Exception {
        List<AbcFile.Tune> tunes = AbcFile.parse("X:1\nT:Test\nC:Bach\nZ:Me\nM:4/4\nL:1/4\nQ:1/4=120\nK:C\nCDEF|GABc|\n");
        assertThat(tunes).hasSize(1);
        AbcFile.Tune t = tunes.get(0);
        assertThat(t.title()).isEqualTo("Test");
        assertThat(t.composer()).isEqualTo("Bach");
        assertThat(t.transcriber()).isEqualTo("Me");
        assertThat(t.durationMillis()).isEqualTo(4000);
        assertThat(pitches(t)).containsExactly(60, 62, 64, 65, 67, 69, 71, 72);
    }

    @Test
    void keySignatureAccidentalsAndBarReset() throws Exception {
        AbcFile.Tune t = AbcFile.parse("X:1\nT:k\nL:1/8\nK:G\nFF ^F F|F=FF\n").get(0);
        assertThat(pitches(t)).containsExactly(66, 66, 66, 66, 66, 65, 65);
    }

    @Test
    void repeatsAndEndingsAreExpanded() throws Exception {
        AbcFile.Tune t = AbcFile.parse("X:1\nT:r\nL:1/4\nK:C\n|:C D |1 E :|2 F |]\n").get(0);
        assertThat(pitches(t)).containsExactly(60, 62, 64, 60, 62, 65);
    }

    @Test
    void lengthsRestsChordsTiesAndBrokenRhythm() throws Exception {
        AbcFile.Tune t = AbcFile.parse("X:1\nT:l\nL:1/4\nM:4/4\nQ:1/4=60\nK:C\nC2 z [CE]- [CE] c>d|\n").get(0);
        assertThat(pitches(t)).containsExactly(60, 60, 64, 72, 74);
        assertThat(t.durationMillis()).isEqualTo(7000);   // 2 + 1 + 2 (tied chord) + 1.5 + 0.5 beats at 60 bpm
    }

    @Test
    void severalTunesAndTheFileHeader() {
        List<AbcFile.Tune> tunes = AbcFile.parse("%abc\nM:3/4\nL:1/8\n\nX:1\nT:One\nK:D\nDEF\n\nX:7\nT:Two\nK:G\nGAB\n\nX:9\nT:Empty\nK:C\n\n");
        assertThat(tunes).extracting(AbcFile.Tune::title).containsExactly("One", "Two");
        assertThat(tunes).extracting(AbcFile.Tune::position).containsExactly(1, 2);
        assertThat(tunes).extracting(AbcFile.Tune::number).containsExactly(1, 7);
    }

    @Test
    void twoVoicesUseTwoChannels() throws Exception {
        AbcFile.Tune t = AbcFile.parse("X:1\nT:v\nL:1/4\nK:C\nV:1\nCDEF|\nV:2\nC,D,E,F,|\n").get(0);
        assertThat(t.sequence().getTracks()).hasSize(3);
    }
}
