package org.kendar.dlna.shuffle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.catalog.TrackTags;
import org.kendar.dlna.shared.RootRef;

class SimilarTitlesTest {

    private static boolean alike(String a, String b) {
        return SimilarTitles.similar(SimilarTitles.key(a), SimilarTitles.key(b));
    }

    private static Track track(String file, String title) {
        TrackTags tags = new TrackTags(title, null, null, null, null, null, null, null, null);
        return new Track(new RootRef("M", file), 0, 0, Format.MP3, tags, 0, 0, 0, 0, false);
    }

    @Test
    void equalAfterNormalization() {
        assertTrue(alike("Sweet Child O' Mine", "sweet child o mine"));
        assertTrue(alike("Café", "CAFE "));
    }

    @Test
    void oneInsideTheOther() {
        assertTrue(alike("Imagine", "Imagine (Remastered 2010)"));
        assertFalse(alike("Love", "Love Me Do and other things"));
    }

    @Test
    void fewCharactersApart() {
        assertTrue(alike("Bohemian Rhapsody", "Bohemian Rapsody"));
        assertFalse(alike("Bohemian Rhapsody", "Another One Bites the Dust"));
    }

    @Test
    void numbersAndShortTitlesNeedToBeEqual() {
        assertFalse(alike("Track 1", "Track 2"));
        assertFalse(alike("Prelude No. 1", "Prelude No. 12"));
        assertFalse(alike("Go", "Gone"));
    }

    @Test
    void distinctSkipsLookAlikesButKeepsTheCount() {
        List<Track> in = List.of(track("a.mp3", "Imagine"), track("b.mp3", "Imagine (Live)"),
                track("c.mp3", "Yesterday"));
        assertEquals(List.of("a.mp3", "c.mp3"), SimilarTitles.distinct(in, 2).stream().map(Track::fileName).toList());
        assertEquals(List.of("a.mp3", "c.mp3", "b.mp3"),
                SimilarTitles.distinct(in, 3).stream().map(Track::fileName).toList());
    }
}
