package org.kendar.dlna.artwork;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.kendar.dlna.shared.ArtNames;

class ArtNamesTest {

    @Test
    void recognisesTheUsualNamesAndTypesCaseInsensitively() {
        for (String n : List.of("front.jpg", "Cover.JPG", "folder.png", "ALBUM.jpeg", "AlbumArt.gif",
                "albumartlarge.bmp")) {
            assertThat(ArtNames.isFront(n)).as(n).isTrue();
        }
        for (String n : List.of("rear.jpg", "Back.PNG")) {
            assertThat(ArtNames.isRear(n)).as(n).isTrue();
        }
        for (String n : List.of("front.webp", "front", ".jpg", "frontcover.jpg", "cover.jpg.bak", "back.jpg ", "x.png")) {
            assertThat(ArtNames.isFront(n) || ArtNames.isRear(n)).as(n).isFalse();
        }
    }

    @Test
    void picksTheBestFileByNameThenType() {
        assertThat(ArtNames.pickFront(List.of("a.mp3", "cover.png", "folder.jpg", "cover.jpg"))).contains("cover.jpg");
        assertThat(ArtNames.pickFront(List.of("folder.jpg", "front.bmp"))).contains("front.bmp");
        assertThat(ArtNames.pickFront(List.of("a.mp3", "b.txt"))).isEmpty();
        assertThat(ArtNames.pickRear(List.of("Back.png", "rear.gif"))).contains("rear.gif");
    }
}
