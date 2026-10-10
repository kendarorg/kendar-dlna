package org.kendar.dlna.artwork;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;

import org.junit.jupiter.api.Test;

class ThumbnailerTest {

    @Test
    void sniffsContentTypes() {
        assertThat(Thumbnailer.sniff(new byte[] { (byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 0, 0, 0 }))
                .isEqualTo("image/png");
        assertThat(Thumbnailer.sniff(new byte[] { 'G', 'I', 'F', '8', '9' })).isEqualTo("image/gif");
        assertThat(Thumbnailer.sniff(new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0 }))
                .isEqualTo("image/jpeg");
        assertThat(Thumbnailer.sniff(new byte[0])).isEqualTo("image/jpeg");
    }

    @Test
    void rejectsNonImages() {
        assertThatThrownBy(() -> Thumbnailer.jpeg(new byte[] { 1, 2, 3 }, 160)).isInstanceOf(IOException.class);
    }
}
