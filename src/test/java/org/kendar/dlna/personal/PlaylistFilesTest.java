package org.kendar.dlna.personal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

class PlaylistFilesTest {

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void m3uKeepsOrderTitlesAndSkipsCommentsAndBlankLines() {
        List<PlaylistFiles.Entry> e = PlaylistFiles.parse(utf8(
                "\uFEFF#EXTM3U\n\n#EXTINF:215,Artist - Song\nA/b.flac\n# a comment\n  C/d.mp3  \n#EXTINF:-1,\nE.ogg\n"));
        assertThat(e).extracting(PlaylistFiles.Entry::location).containsExactly("A/b.flac", "C/d.mp3", "E.ogg");
        assertThat(e).extracting(PlaylistFiles.Entry::title).containsExactly("Artist - Song", null, null);
    }

    @Test
    void oldM3uInLatin1IsDecodedAndUtf8Wins() {
        byte[] latin = "caf\u00e9/na\u00efve.mp3\n".getBytes(StandardCharsets.ISO_8859_1);
        assertThat(PlaylistFiles.parse(latin).get(0).location()).isEqualTo("caf\u00e9/na\u00efve.mp3");
        assertThat(PlaylistFiles.parse(utf8("caf\u00e9/x.mp3\n")).get(0).location()).isEqualTo("caf\u00e9/x.mp3");
    }

    @Test
    void plsIsOrderedByEntryNumber() {
        List<PlaylistFiles.Entry> e = PlaylistFiles.parse(utf8(
                "[playlist]\nFile10=ten.mp3\nFile2=two.mp3\nTitle2=Two\nFILE1=one.mp3\nNumberOfEntries=3\nVersion=2\n"));
        assertThat(e).extracting(PlaylistFiles.Entry::location).containsExactly("one.mp3", "two.mp3", "ten.mp3");
        assertThat(e.get(1).title()).isEqualTo("Two");
    }

    @Test
    void locationsAreReducedToPathSegments() {
        assertThat(PlaylistFiles.segments("C:\\Music\\A\\b.flac")).containsExactly("Music", "A", "b.flac");
        assertThat(PlaylistFiles.segments("./../x/y.mp3")).containsExactly("x", "y.mp3");
        assertThat(PlaylistFiles.segments("file:///home/me/a%20b/c.mp3")).containsExactly("home", "me", "a b", "c.mp3");
        assertThat(PlaylistFiles.segments("https://host/x.mp3")).isEmpty();
        assertThat(PlaylistFiles.segments("  ")).isEmpty();
    }

    @Test
    void writtenFilesReadBackAndHaveSafeNames() {
        List<PlaylistFiles.Item> items = List.of(new PlaylistFiles.Item("Root/a.flac", "A - One\nline", 215_400),
                new PlaylistFiles.Item("Root/b.mp3", "B", 0));
        for (PlaylistFiles.Format f : PlaylistFiles.Format.values()) {
            PlaylistFiles.Output out = PlaylistFiles.write(f, "My: mix/1", items);
            assertThat(out.fileName()).startsWith("My_ mix_1.");
            assertThat(PlaylistFiles.parse(out.body())).extracting(PlaylistFiles.Entry::location)
                    .containsExactly("Root/a.flac", "Root/b.mp3");
        }
        String m3u = new String(PlaylistFiles.write(PlaylistFiles.Format.M3U8, "x", items).body(), StandardCharsets.UTF_8);
        assertThat(m3u).contains("#EXTINF:215,A - One line").contains("#EXTINF:-1,B");
        assertThat(PlaylistFiles.Format.parse(null)).isEqualTo(PlaylistFiles.Format.M3U8);
    }
}
