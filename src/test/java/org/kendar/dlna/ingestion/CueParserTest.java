package org.kendar.dlna.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.kendar.dlna.ingestion.CueSheet.CueFile;
import org.kendar.dlna.ingestion.CueSheet.CueTrack;
import org.kendar.dlna.testutil.CueFixtures;

class CueParserTest {

    @Test
    void parsesAStandardSheet() {
        CueSheet s = CueParser.parse(CueFixtures.CUE);

        assertThat(s.title()).isEqualTo("Live Album");
        assertThat(s.performer()).isEqualTo("The Band");
        assertThat(s.genre()).isEqualTo("Rock");
        assertThat(s.year()).isEqualTo(1999);
        assertThat(s.warnings()).isEmpty();
        assertThat(s.files()).hasSize(1);
        CueFile f = s.files().get(0);
        assertThat(f.name()).isEqualTo("disc.wav");
        assertThat(f.tracks()).extracting(CueTrack::title).containsExactly("Opening", "Middle", "Finale");
        assertThat(f.tracks()).extracting(CueTrack::performer).containsExactly("The Band", "Guest", null);
        assertThat(f.tracks()).extracting(CueTrack::number).containsExactly(1, 2, 3);
        // 75 frames per second: 03:37 = 3 s + 37/75 s
        assertThat(f.tracks().get(1).startMicros()).isEqualTo(3_493_333L);
        assertThat(f.tracks().get(1).gapMicros()).isEqualTo(3_000_000L);
        assertThat(f.tracks().get(0).gapMicros()).isEqualTo(-1);
        assertThat(f.tracks().get(2).startMicros()).isEqualTo(6_000_000L);
    }

    @Test
    void skipsAUtf8BomAndAcceptsLowerCaseCommandsAndAllLineEndings() {
        String text = "﻿performer \"A\"\rtitle \"B\"\rfile \"x.flac\" wave\r\n track 1 audio\n  index 01 00:00:00\n";
        CueSheet s = CueParser.parse((text).getBytes(StandardCharsets.UTF_8));
        assertThat(s.performer()).isEqualTo("A");
        assertThat(s.title()).isEqualTo("B");
        assertThat(s.files().get(0).name()).isEqualTo("x.flac");
        assertThat(s.files().get(0).tracks()).hasSize(1);
    }

    @Test
    void decodesLatin1WhenTheBytesAreNotUtf8() {
        String text = "TITLE \"Café del Mar\"\nFILE \"a.wav\" WAVE\n TRACK 01 AUDIO\n  TITLE \"Niño\"\n  INDEX 01 00:00:00\n";
        CueSheet s = CueParser.parse(text.getBytes(Charset.forName("windows-1252")));
        assertThat(s.title()).isEqualTo("Café del Mar");
        assertThat(s.files().get(0).tracks().get(0).title()).isEqualTo("Niño");
    }

    @Test
    void decodesUtf16WithBom() {
        String text = "TITLE \"Über\"\nFILE \"a.wav\" WAVE\n TRACK 01 AUDIO\n  INDEX 01 00:00:00\n";
        byte[] body = text.getBytes(StandardCharsets.UTF_16LE);
        byte[] all = new byte[body.length + 2];
        all[0] = (byte) 0xFF;
        all[1] = (byte) 0xFE;
        System.arraycopy(body, 0, all, 2, body.length);
        assertThat(CueParser.parse(all).title()).isEqualTo("Über");
    }

    @Test
    void fileNamesMayBeQuotedUnquotedSpacedOrContainQuotes() {
        assertThat(CueParser.fileNameOf("\"01 My Song.wav\" WAVE")).isEqualTo("01 My Song.wav");
        assertThat(CueParser.fileNameOf("01 My Song.wav WAVE")).isEqualTo("01 My Song.wav");
        assertThat(CueParser.fileNameOf("plain.flac")).isEqualTo("plain.flac");
        assertThat(CueParser.fileNameOf("\"a \"b\" c.wav\" WAVE")).isEqualTo("a \"b\" c.wav");
        assertThat(CueParser.fileNameOf("\"unterminated.wav WAVE")).isEqualTo("unterminated.wav WAVE");
        assertThat(CueParser.fileNameOf("   ")).isNull();
    }

    @Test
    void severalFilesKeepTheirOwnTracksAndTrackNumbersContinue() {
        String text = """
                TITLE "Two files"
                FILE "a.wav" WAVE
                  TRACK 01 AUDIO
                    INDEX 01 00:00:00
                  TRACK 02 AUDIO
                    INDEX 01 02:00:00
                FILE "b.wav" WAVE
                  TRACK 03 AUDIO
                    INDEX 01 00:00:00
                """;
        CueSheet s = CueParser.parse(text);
        assertThat(s.files()).extracting(CueFile::name).containsExactly("a.wav", "b.wav");
        assertThat(s.files().get(0).tracks()).extracting(CueTrack::number).containsExactly(1, 2);
        assertThat(s.files().get(1).tracks()).extracting(CueTrack::number).containsExactly(3);
        assertThat(s.files().get(1).tracks().get(0).startMicros()).isZero();
    }

    @Test
    void oddInputIsToleratedWithWarnings() {
        String text = """
                GARBAGE here
                TRACK 01 AUDIO
                  INDEX 01 xx:yy:zz
                TRACK 02 MODE1/2352
                  INDEX 01 00:10:00
                FILE "a.wav" WAVE
                  TRACK 03 AUDIO
                    TITLE "no index"
                  TRACK 04 AUDIO
                    INDEX 01 05:00:00
                  TRACK 05 AUDIO
                    INDEX 01 04:00:00
                  TRACK 06 AUDIO
                    INDEX 01 00:99:00
                """;
        CueSheet s = CueParser.parse(text);

        assertThat(s.warnings()).anyMatch(w -> w.contains("unknown command 'GARBAGE'"))
                .anyMatch(w -> w.contains("TRACK before any FILE"))
                .anyMatch(w -> w.contains("invalid INDEX"))
                .anyMatch(w -> w.contains("track 2 is not an audio track"))
                .anyMatch(w -> w.contains("track 3 has no INDEX 01"))
                .anyMatch(w -> w.contains("track 5 of 'a.wav' does not start after"));
        // the track before FILE has an invalid index, so the first (nameless) file is empty
        CueFile a = s.files().stream().filter(f -> "a.wav".equals(f.name())).findFirst().orElseThrow();
        assertThat(a.tracks()).extracting(CueTrack::number).containsExactly(4);
    }

    @Test
    void emptyAndMissingFileNamesNeverThrow() {
        assertThat(CueParser.parse("").files()).isEmpty();
        assertThat(CueParser.parse(new byte[0]).files()).isEmpty();
        CueSheet s = CueParser.parse("FILE\nTRACK 01 AUDIO\nINDEX 01 00:00:00\n");
        assertThat(s.warnings()).anyMatch(w -> w.contains("FILE without a name"));
        assertThat(s.files().get(0).name()).isNull();
        assertThat(s.files().get(0).tracks()).hasSize(1);
    }

    @Test
    void timesAcceptFramesAndSeconds() {
        assertThat(CueParser.time("00:00:00")).isZero();
        assertThat(CueParser.time("74:59:74")).isEqualTo(((74L * 60 + 59) * 75 + 74) * 1_000_000L / 75);
        assertThat(CueParser.time("01:30.5")).isEqualTo(90_500_000L);
        assertThat(CueParser.time("01:60:00")).isEqualTo(-1);
        assertThat(CueParser.time("01:00:75")).isEqualTo(-1);
        assertThat(CueParser.time("nonsense")).isEqualTo(-1);
        assertThat(CueParser.time("5")).isEqualTo(-1);
    }

    @Test
    void remFieldsAreReadAndTheDateKeepsItsLeadingYear() {
        CueSheet s = CueParser.parse("REM GENRE Jazz Fusion\nREM DATE 2001-05-03\nREM DISCNUMBER 2\nREM COMPOSER \"Bach\"\n"
                + "FILE a.wav WAVE\nTRACK 1 AUDIO\nINDEX 01 00:00:00\n");
        assertThat(s.genre()).isEqualTo("Jazz Fusion");
        assertThat(s.year()).isEqualTo(2001);
        assertThat(s.discNumber()).isEqualTo(2);
        assertThat(s.songwriter()).isEqualTo("Bach");
    }

    @Test
    void trackLevelTitleDoesNotOverwriteTheAlbumTitle() {
        CueSheet s = CueParser.parse("TITLE \"Album\"\nFILE a.wav WAVE\nTRACK 1 AUDIO\nTITLE \"T1\"\nINDEX 01 00:00:00\n");
        assertThat(s.title()).isEqualTo("Album");
        assertThat(s.files().get(0).tracks().get(0).title()).isEqualTo("T1");
    }
}
