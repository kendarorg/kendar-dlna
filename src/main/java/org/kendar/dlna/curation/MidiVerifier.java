package org.kendar.dlna.curation;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.Track;

import org.kendar.dlna.catalog.TrackTags;
import org.kendar.dlna.ingestion.MidiTagReader;

/**
 * Checks a patched MIDI file against the original before it may replace it: both parse with
 * {@code javax.sound.midi}; division, track count, tick length and microsecond length are equal; apart from the text
 * events the edit is allowed to touch every event is identical (same tick, same raw bytes, same order); and the tags
 * read back are exactly the original ones with the requested changes applied.
 */
final class MidiVerifier {

    private MidiVerifier() {
    }

    static void verify(byte[] original, byte[] patched, TagEdit edit) throws TagWriter.TagWriteException {
        Sequence before = parse(original, "original");
        Sequence after = parse(patched, "patched copy");
        if (before.getDivisionType() != after.getDivisionType() || before.getResolution() != after.getResolution()) {
            throw fail("the time division changed");
        }
        Track[] bt = before.getTracks();
        Track[] at = after.getTracks();
        if (bt.length != at.length) {
            throw fail("the number of tracks changed (" + bt.length + " -> " + at.length + ")");
        }
        if (before.getTickLength() != after.getTickLength()) {
            throw fail("the tick length changed (" + before.getTickLength() + " -> " + after.getTickLength() + ")");
        }
        if (before.getMicrosecondLength() != after.getMicrosecondLength()) {
            throw fail("the duration changed");
        }
        for (int t = 0; t < bt.length; t++) {
            List<String> a = events(bt[t], t, edit);
            List<String> b = events(at[t], t, edit);
            if (!a.equals(b)) {
                int i = 0;
                while (i < a.size() && i < b.size() && a.get(i).equals(b.get(i))) {
                    i++;
                }
                throw fail("track " + t + " differs in an event that was not to be edited (event " + i + ")");
            }
        }
        TrackTags was = MidiTagReader.tags(before);
        TrackTags expected = new TrackTags(
                edit.title() == null ? was.title() : edit.title().isEmpty() ? null : edit.title(),
                edit.artist() == null ? was.artist() : edit.artist().isEmpty() ? null : edit.artist(),
                was.album(), was.albumArtist(), was.genre(),
                edit.year() == null ? was.year() : edit.year().isEmpty() ? null : Integer.valueOf(edit.year()),
                was.trackNo(), was.discNo(), was.discTotal());
        TrackTags got = MidiTagReader.tags(after);
        if (!expected.equals(got)) {
            throw fail("the tags read back differ from the request: expected title=" + expected.title() + ", artist="
                    + expected.artist() + ", year=" + expected.year() + " but found title=" + got.title()
                    + ", artist=" + got.artist() + ", year=" + got.year());
        }
    }

    private static Sequence parse(byte[] bytes, String what) throws TagWriter.TagWriteException {
        try {
            return MidiSystem.getSequence(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw fail("the " + what + " cannot be read as MIDI: " + e.getMessage());
        }
    }

    /** Tick and raw bytes of every event, except the text events this edit may change. */
    private static List<String> events(Track track, int trackIndex, TagEdit edit) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < track.size(); i++) {
            MidiEvent e = track.get(i);
            if (e.getMessage() instanceof MetaMessage m && editable(m, trackIndex, edit)) {
                continue;
            }
            out.add(e.getTick() + ":" + Arrays.toString(e.getMessage().getMessage()));
        }
        return out;
    }

    private static boolean editable(MetaMessage m, int trackIndex, TagEdit edit) {
        return switch (m.getType()) {
            case 0x03 -> trackIndex == 0 && edit.title() != null;
            case 0x02 -> edit.year() != null;
            case 0x01 -> (edit.title() != null || edit.artist() != null)
                    && MidiTagReader.decode(m.getData()).strip().startsWith("@T");
            default -> false;
        };
    }

    private static TagWriter.TagWriteException fail(String why) {
        return new TagWriter.TagWriteException("MIDI edit rejected, the original is untouched: " + why, null);
    }
}
