package org.kendar.dlna.curation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;

import org.junit.jupiter.api.Test;
import org.kendar.dlna.catalog.TrackTags;
import org.kendar.dlna.ingestion.MidiTagReader;

/** Byte-level tests of the MIDI tag patcher on hand-built and generated Standard MIDI Files. */
class MidiPatcherTest {

    // ---- SMF building blocks ----

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    static byte[] vlq(int v) {
        byte[] tmp = new byte[5];
        int n = 0;
        tmp[n++] = (byte) (v & 0x7F);
        while ((v >>>= 7) > 0) {
            tmp[n++] = (byte) ((v & 0x7F) | 0x80);
        }
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = tmp[n - 1 - i];
        }
        return out;
    }

    static byte[] raw(int delta, int... bytes) {
        byte[] b = new byte[bytes.length];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) bytes[i];
        }
        return cat(vlq(delta), b);
    }

    static byte[] meta(int delta, int type, byte[] data) {
        return cat(vlq(delta), new byte[] { (byte) 0xFF, (byte) type }, vlq(data.length), data);
    }

    static byte[] text(int delta, int type, String s) {
        return meta(delta, type, s.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] eot() {
        return raw(0, 0xFF, 0x2F, 0x00);
    }

    static byte[] mtrk(byte[]... events) {
        byte[] body = cat(events);
        return cat("MTrk".getBytes(StandardCharsets.US_ASCII), u32(body.length), body);
    }

    static byte[] u32(int v) {
        return new byte[] { (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v };
    }

    static byte[] smf(int format, byte[]... tracks) {
        return cat("MThd".getBytes(StandardCharsets.US_ASCII), u32(6),
                new byte[] { 0, (byte) format, (byte) (tracks.length >> 8), (byte) tracks.length, 0x01, (byte) 0xE0 },
                cat(tracks));
    }

    /** Notes with running status, 480 ticks per quarter note. */
    static byte[] notes() {
        return cat(raw(0, 0x90, 60, 100), raw(0x60, 60, 0), raw(0x10, 0x80, 60, 0), raw(0, 0x90, 64, 100),
                raw(0x81, 64, 0));
    }

    static TagEdit edit(String title, String artist, String year) {
        return new TagEdit(title, artist, null, null, null, year, null, null, null);
    }

    static TrackTags tags(byte[] smf) throws Exception {
        return MidiTagReader.tags(MidiSystem.getSequence(new ByteArrayInputStream(smf)));
    }

    /** Patches and runs the full verification the writer runs. */
    static byte[] apply(byte[] original, TagEdit edit) throws Exception {
        byte[] patched = MidiPatcher.patch(original, edit);
        MidiVerifier.verify(original, patched, edit);
        return patched;
    }

    static int trackLengthField(byte[] smf, int chunkStart) {
        return ((smf[chunkStart + 4] & 0xFF) << 24) | ((smf[chunkStart + 5] & 0xFF) << 16)
                | ((smf[chunkStart + 6] & 0xFF) << 8) | (smf[chunkStart + 7] & 0xFF);
    }

    private static boolean contains(byte[] haystack, String needle) {
        return new String(haystack, StandardCharsets.ISO_8859_1).contains(needle);
    }

    // ---- title ----

    @Test
    void replacesTheSequenceNameInPlaceAndFixesLengths() throws Exception {
        byte[] head = raw(0, 0xFF, 0x51, 0x03, 0x07, 0xA1, 0x20);
        byte[] orig = smf(0, mtrk(head, text(0, 0x03, "Old Title"), notes(), eot()));
        byte[] shorter = apply(orig, edit("X", null, null));
        byte[] longer = apply(orig, edit("A much longer title than before", null, null));

        assertThat(tags(shorter).title()).isEqualTo("X");
        assertThat(tags(longer).title()).isEqualTo("A much longer title than before");
        assertThat(trackLengthField(shorter, 14)).isEqualTo(shorter.length - 22);
        assertThat(trackLengthField(longer, 14)).isEqualTo(longer.length - 22);
        // file header and the events before the replaced one are byte-identical
        assertThat(Arrays.copyOf(longer, 18)).isEqualTo(Arrays.copyOf(orig, 18));
        assertThat(Arrays.copyOfRange(longer, 22, 22 + head.length)).isEqualTo(head);
        // and so is everything after it
        byte[] tail = cat(notes(), eot());
        assertThat(Arrays.copyOfRange(longer, longer.length - tail.length, longer.length)).isEqualTo(tail);
        assertThat(Arrays.copyOfRange(shorter, shorter.length - tail.length, shorter.length)).isEqualTo(tail);
    }

    @Test
    void vlqLengthCrossesTheOneAndTwoByteBoundariesBothWays() throws Exception {
        byte[] previous = smf(1, mtrk(text(0, 0x03, "t"), notes(), eot()));
        for (int len : new int[] { 126, 127, 128, 16383, 16384, 130, 5 }) {
            String title = "a".repeat(len);
            byte[] patched = apply(previous, edit(title, null, null));
            assertThat(tags(patched).title()).as("length " + len).hasSize(len);
            assertThat(trackLengthField(patched, 14)).isEqualTo(patched.length - 22);
            previous = patched;
        }
    }

    @Test
    void insertsAMissingTitleAtTheStartOfTheFirstTrackWithDeltaZero() throws Exception {
        byte[] body = cat(notes(), eot());
        byte[] orig = smf(0, mtrk(notes(), eot()));
        byte[] patched = apply(orig, edit("Hello", null, null));

        byte[] inserted = text(0, 0x03, "Hello");
        assertThat(patched).isEqualTo(
                cat(Arrays.copyOf(orig, 18), u32(body.length + inserted.length), inserted, body));
        assertThat(tags(patched).title()).isEqualTo("Hello");
    }

    @Test
    void clearingTheTitleRemovesTheEventAndKeepsTheNextEventAtItsTick() throws Exception {
        byte[] orig = smf(0, mtrk(raw(0, 0xFF, 0x51, 0x03, 0x07, 0xA1, 0x20), text(0x30, 0x03, "Gone"),
                raw(0x90, 0x90, 60, 100), raw(0x60, 60, 0), eot()));
        byte[] patched = apply(orig, edit("", null, null));
        assertThat(tags(patched).title()).isNull();
        assertThat(MidiSystem.getSequence(new ByteArrayInputStream(patched)).getTickLength())
                .isEqualTo(MidiSystem.getSequence(new ByteArrayInputStream(orig)).getTickLength());
    }

    @Test
    void onlyTheFirstTrackNamesTheSequence() throws Exception {
        byte[] orig = smf(1, mtrk(text(0, 0x03, "Song"), eot()), mtrk(text(0, 0x03, "Piano"), notes(), eot()));
        byte[] patched = apply(orig, edit("Other", null, "1999"));
        assertThat(tags(patched).title()).isEqualTo("Other");
        byte[] track2 = mtrk(text(0, 0x03, "Piano"), notes(), eot());
        assertThat(Arrays.copyOfRange(patched, patched.length - track2.length, patched.length)).isEqualTo(track2);
    }

    // ---- karaoke ----

    private static byte[] karaoke() {
        return smf(1, mtrk(text(0, 0x01, "@KMIDI KARAOKE FILE"), text(0, 0x01, "@LENGL"),
                text(0, 0x01, "@TYesterday"), text(0, 0x01, "@TThe Beatles"), text(0, 0x01, "@TTranscribed by Bob"),
                eot()), mtrk(raw(0, 0x90, 60, 100), raw(0x60, 60, 0), text(0, 0x01, "/Yes-"), eot()));
    }

    @Test
    void titleKeepsTheFirstKaraokeLineConsistentAndArtistReplacesTheSecond() throws Exception {
        byte[] patched = apply(karaoke(), edit("Help!", "The Fab Four", null));
        TrackTags t = tags(patched);
        assertThat(t.title()).isEqualTo("Help!");
        assertThat(t.artist()).isEqualTo("The Fab Four");
        assertThat(contains(patched, "@THelp!")).isTrue();
        assertThat(contains(patched, "@TYesterday")).isFalse();
        assertThat(contains(patched, "@TTranscribed by Bob")).isTrue();
        assertThat(contains(patched, "@KMIDI KARAOKE FILE")).isTrue();
    }

    @Test
    void artistAloneCreatesTheTitleLineFromTheSequenceNameThenTheArtistLine() throws Exception {
        byte[] orig = smf(0, mtrk(text(0, 0x03, "Imagine"), notes(), eot()));
        byte[] patched = apply(orig, edit(null, "John Lennon", null));
        TrackTags t = tags(patched);
        assertThat(t.title()).isEqualTo("Imagine");
        assertThat(t.artist()).isEqualTo("John Lennon");
        assertThat(contains(patched, "@TImagine")).isTrue();
    }

    @Test
    void artistWithAnExistingTitleLineAddsTheSecondLineRightAfterIt() throws Exception {
        byte[] orig = smf(0, mtrk(text(0, 0x01, "@TOnly Title"), notes(), eot()));
        byte[] patched = apply(orig, edit(null, "Somebody", null));
        assertThat(tags(patched).artist()).isEqualTo("Somebody");
        assertThat(tags(patched).title()).isEqualTo("Only Title");
    }

    @Test
    void anArtistWithoutAnyTitleIsRefused() {
        byte[] orig = smf(0, mtrk(notes(), eot()));
        assertThatThrownBy(() -> MidiPatcher.patch(orig, edit(null, "Nobody", null)))
                .isInstanceOf(TagWriter.TagWriteException.class).hasMessageContaining("needs a title");
        assertThatThrownBy(() -> MidiPatcher.patch(karaoke(), edit("", null, null)))
                .isInstanceOf(TagWriter.TagWriteException.class).hasMessageContaining("needs a title");
    }

    @Test
    void titleAndArtistTogetherOnAPlainFileCreateAllThree() throws Exception {
        byte[] patched = apply(smf(0, mtrk(notes(), eot())), edit("Song", "Band", "2005"));
        TrackTags t = tags(patched);
        assertThat(t.title()).isEqualTo("Song");
        assertThat(t.artist()).isEqualTo("Band");
        assertThat(t.year()).isEqualTo(2005);
    }

    @Test
    void clearingTheArtistRemovesTheSecondLine() throws Exception {
        byte[] orig = smf(0, mtrk(text(0, 0x01, "@TTitle"), text(0, 0x01, "@TArtist"), notes(), eot()));
        byte[] patched = apply(orig, edit(null, "", null));
        assertThat(tags(patched).artist()).isNull();
        assertThat(tags(patched).title()).isEqualTo("Title");
    }

    // ---- year ----

    @Test
    void yearIsReplacedInsideAnExistingCopyrightText() throws Exception {
        byte[] orig = smf(0, mtrk(text(0, 0x02, "(c) 1996 Sony Music"), notes(), eot()));
        byte[] patched = apply(orig, edit(null, null, "2003"));
        assertThat(tags(patched).year()).isEqualTo(2003);
        assertThat(contains(patched, "(c) 2003 Sony Music")).isTrue();
    }

    @Test
    void copyrightWithoutAYearGetsOneInFrontAndAMissingOneIsCreated() throws Exception {
        byte[] withText = apply(smf(0, mtrk(text(0, 0x02, "Sony Music"), notes(), eot())), edit(null, null, "2003"));
        assertThat(contains(withText, "(c) 2003 Sony Music")).isTrue();
        byte[] created = apply(smf(0, mtrk(notes(), eot())), edit(null, null, "1987"));
        assertThat(contains(created, "(c) 1987")).isTrue();
        assertThat(tags(created).year()).isEqualTo(1987);
    }

    @Test
    void clearingTheYearKeepsAnOwnerAndRemovesABareNotice() throws Exception {
        byte[] owner = apply(smf(0, mtrk(text(0, 0x02, "(c) 1996 Sony Music"), notes(), eot())), edit(null, null, ""));
        assertThat(tags(owner).year()).isNull();
        assertThat(contains(owner, "(c) Sony Music")).isTrue();
        byte[] bare = apply(smf(0, mtrk(text(0, 0x02, "(c) 1996"), notes(), eot())), edit(null, null, ""));
        assertThat(tags(bare).year()).isNull();
        assertThat(contains(bare, "(c)")).isFalse();
    }

    @Test
    void yearsOutsideWhatTheReaderRecognisesAreRefused() {
        byte[] orig = smf(0, mtrk(notes(), eot()));
        assertThatThrownBy(() -> MidiPatcher.patch(orig, edit(null, null, "1850")))
                .isInstanceOf(TagWriter.TagWriteException.class).hasMessageContaining("1900");
    }

    // ---- encoding ----

    @Test
    void aLatin1EventStaysLatin1WhenTheNewTextFitsElseBecomesUtf8() throws Exception {
        byte[] latin = meta(0, 0x03, "Café".getBytes(StandardCharsets.ISO_8859_1));
        byte[] orig = smf(0, mtrk(latin, notes(), eot()));
        assertThat(tags(orig).title()).isEqualTo("Café");

        byte[] fits = apply(orig, edit("Zéro", null, null));
        assertThat(tags(fits).title()).isEqualTo("Zéro");
        assertThat(contains(fits, "Zéro")).isTrue();   // read as ISO-8859-1: a single E9 byte

        byte[] wide = apply(orig, edit("日本", null, null));
        assertThat(tags(wide).title()).isEqualTo("日本");
    }

    @Test
    void utf8TitlesAreWrittenAsUtf8() throws Exception {
        byte[] patched = apply(smf(0, mtrk(text(0, 0x03, "Old"), notes(), eot())), edit("Café ♥", null, null));
        assertThat(tags(patched).title()).isEqualTo("Café ♥");
    }

    // ---- formats and generated sequences ----

    @Test
    void format0AndFormat1AreHandled() throws Exception {
        for (int format : new int[] { 0, 1 }) {
            byte[] orig = format == 0 ? smf(0, mtrk(notes(), eot()))
                    : smf(format, mtrk(text(0, 0x03, "Name"), eot()), mtrk(notes(), eot()));
            byte[] patched = apply(orig, edit("T" + format, "A" + format, "2010"));
            assertThat(tags(patched)).extracting(TrackTags::title, TrackTags::artist, TrackTags::year)
                    .containsExactly("T" + format, "A" + format, 2010);
        }
    }

    @Test
    void generatedSequencesOfBothTypesRoundTripWithEveryOtherEventIdentical() throws Exception {
        for (int type : new int[] { 0, 1 }) {
            Sequence seq = new Sequence(Sequence.PPQ, 96, type == 0 ? 1 : 3);
            for (int t = 0; t < seq.getTracks().length; t++) {
                long tick = 0;
                for (int i = 0; i < 40; i++) {
                    seq.getTracks()[t].add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, t, 40 + i, 90), tick));
                    seq.getTracks()[t]
                            .add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, t, 40 + i, 0), tick + 50));
                    tick += 96;
                }
            }
            byte[] n = "Name".getBytes(StandardCharsets.UTF_8);
            seq.getTracks()[0].add(new MidiEvent(new MetaMessage(0x03, n, n.length), 0));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MidiSystem.write(seq, type, out);
            byte[] orig = out.toByteArray();

            byte[] patched = apply(orig, edit("Generated " + type, "Gen Artist", "1999"));
            TrackTags t = tags(patched);
            assertThat(t.title()).isEqualTo("Generated " + type);
            assertThat(t.artist()).isEqualTo("Gen Artist");
            assertThat(t.year()).isEqualTo(1999);
            // an edit that changes nothing keeps all bytes
            assertThat(MidiPatcher.patch(orig, edit(null, null, null))).isEqualTo(orig);
        }
    }

    @Test
    void runningStatusOfTheFollowingEventsSurvivesAnInsertionAtTheStart() throws Exception {
        byte[] orig = smf(0, mtrk(raw(0, 0x90, 60, 100), raw(0x10, 62, 100), raw(0x10, 64, 100), eot()));
        byte[] patched = apply(orig, edit("T", "A", "2001"));
        assertThat(tags(patched).title()).isEqualTo("T");
        assertThat(MidiSystem.getSequence(new ByteArrayInputStream(patched)).getTracks()[0].size())
                .isGreaterThan(MidiSystem.getSequence(new ByteArrayInputStream(orig)).getTracks()[0].size());
    }

    // ---- refusals ----

    private static void refused(byte[] bad, String message) {
        assertThatThrownBy(() -> MidiPatcher.patch(bad, edit("T", null, null)))
                .isInstanceOf(TagWriter.TagWriteException.class).hasMessageContaining(message);
    }

    @Test
    void refusesCorruptOrUnexpectedStructures() {
        byte[] good = smf(0, mtrk(notes(), eot()));
        refused(new byte[] { 1, 2, 3 }, "MThd");
        refused(Arrays.copyOf(good, good.length - 3), "longer than the file");
        refused(Arrays.copyOf(good, 20), "truncated chunk header");
        refused(cat(good, new byte[] { 1, 2, 3 }), "truncated chunk header");

        byte[] badHeader = good.clone();
        badHeader[7] = 7;
        refused(badHeader, "MThd length");

        refused(Arrays.copyOf(good, 14), "no MTrk");
        refused(smf(0, mtrk(notes())), "end-of-track");
        refused(smf(0, mtrk(notes(), eot(), raw(0, 0x90, 1, 1))), "after the end-of-track");
        refused(smf(0, mtrk(raw(0, 0x40, 0x40), eot())), "running status");
        refused(smf(0, mtrk(raw(0, 0x90, 0x40), eot())), "truncated channel event");
        refused(smf(0, mtrk(raw(0, 0xFF, 0x03, 0x20, 0x41), eot())), "longer than its track");

        byte[] wrongCount = good.clone();
        wrongCount[11] = 2;
        refused(wrongCount, "announces 2 tracks");

        byte[] riff = cat("RIFF".getBytes(StandardCharsets.US_ASCII), u32(good.length + 12),
                "RMIDdata".getBytes(StandardCharsets.US_ASCII), u32(good.length), good);
        refused(riff, "RIFF");
    }

    @Test
    void theVerifierRejectsAPatchThatChangesAnotherEventOrTheTags() throws Exception {
        byte[] orig = smf(0, mtrk(text(0, 0x03, "Old"), notes(), eot()));
        byte[] wrongTags = MidiPatcher.patch(orig, edit("Other", null, null));
        assertThatThrownBy(() -> MidiVerifier.verify(orig, wrongTags, edit("Expected", null, null)))
                .isInstanceOf(TagWriter.TagWriteException.class).hasMessageContaining("tags read back");

        byte[] tampered = MidiPatcher.patch(orig, edit("New", null, null));
        tampered[tampered.length - 6] ^= 1;   // a note event changes
        assertThatThrownBy(() -> MidiVerifier.verify(orig, tampered, edit("New", null, null)))
                .isInstanceOf(TagWriter.TagWriteException.class);

        assertThatThrownBy(() -> MidiVerifier.verify(orig, Arrays.copyOf(orig, 10), edit("New", null, null)))
                .isInstanceOf(TagWriter.TagWriteException.class).hasMessageContaining("cannot be read");
    }
}
