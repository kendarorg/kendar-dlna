package org.kendar.dlna.adminweb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.kendar.dlna.testutil.Fixtures.folder;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.kendar.dlna.catalog.AlbumInfo;
import org.kendar.dlna.catalog.DirectorySettings;

class DtoTest {

    @Test
    void settingsCarryTheDisplayName() {
        DirectorySettings s = new DirectorySettings(null, null, null, 0, null, 0, "  Shown ");
        assertThat(Dto.settings(s)).containsEntry("displayName", "Shown");
        assertThat(Dto.settings(DirectorySettings.DEFAULT)).containsKey("displayName").containsEntry("displayName", null);
    }

    @Test
    void folderRefUsesTheDisplayNameButKeepsTheRealName() {
        Map<String, Object> m = Dto.folderRef(folder("m/Dir", new DirectorySettings(null, null,
                new AlbumInfo("Album", null, null, null, 2, null), 0, null, 0, "Custom")));
        assertThat(m).containsEntry("name", "Dir").containsEntry("displayName", "Custom")
                .containsEntry("path", "m/Dir");
        assertThat(Dto.folderRef(folder("m/Dir"))).containsEntry("displayName", "Dir");
    }
}
