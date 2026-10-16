package org.kendar.dlna.curation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.shared.RootRef;
import org.kendar.dlna.testutil.LibraryFixture;

class BulkTagsTest {

    @TempDir
    Path tmp;
    LibraryFixture lib;
    TagService tags;
    BulkTags bulk;
    final RootRef album = RootRef.parse("main/Album");
    final TagEdit genre = new TagEdit(null, null, null, null, "Bulk Genre", null, null, null, null);

    @BeforeEach
    void setUp() {
        lib = new LibraryFixture(tmp);
        tags = new TagService(lib.catalog, lib.files, new PerFormatTagWriter(), lib.reader);
        bulk = tags.bulk();
        bulk.changeLog(tmp.resolve("data/tag-changes.log"));
    }

    private int editableTracks() {
        return (int) lib.catalog.current().subtreeTracks(album).stream()
                .filter(t -> !t.isSegment() && t.format().tagsEditable()
                        && t.format().editableFields().contains("genre")).count();
    }

    private BulkTags.Status waitDone() throws InterruptedException {
        for (int i = 0; i < 200 && bulk.status().running(); i++) {
            Thread.sleep(50);
        }
        assertThat(bulk.status().running()).isFalse();
        return bulk.status();
    }

    @Test
    void previewCountsTheEditableFilesWithoutWritingAnything() {
        BulkTags.Preview p = bulk.preview(album, genre);
        assertThat(p.files()).isEqualTo(editableTracks()).isPositive();
        assertThat(p.files() + p.notEditable() + p.notApplicable() + p.segments()).isEqualTo(4);
        assertThat(lib.catalog.current().subtreeTracks(album)).noneMatch(t -> "Bulk Genre".equals(t.tags().genre()));
    }

    @Test
    void startNeedsTheConsentAndTheExactPreviewedCount() {
        int n = bulk.preview(album, genre).files();
        assertThatThrownBy(() -> bulk.start(album, genre, "i consent", n)).isInstanceOf(CurationException.class)
                .extracting(e -> ((CurationException) e).code()).isEqualTo(CurationException.INVALID);
        assertThatThrownBy(() -> bulk.start(album, genre, "I CONSENT", n + 1)).isInstanceOf(CurationException.class)
                .extracting(e -> ((CurationException) e).code()).isEqualTo(CurationException.CONFLICT);
        assertThatThrownBy(() -> bulk.preview(album, new TagEdit(null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(CurationException.class);
        assertThatThrownBy(() -> bulk.preview(RootRef.parse("main/Nope"), genre)).isInstanceOf(CurationException.class);
        assertThat(bulk.status().running()).isFalse();
    }

    @Test
    void appliesInTheBackgroundAndLogsTheOldValues() throws Exception {
        int n = bulk.preview(album, genre).files();
        BulkTags.Status started = bulk.start(album, genre, "I CONSENT", n);
        assertThat(started.total()).isEqualTo(n);

        BulkTags.Status done = waitDone();
        assertThat(done.error()).isNull();
        assertThat(done.done()).isEqualTo(n);
        assertThat(done.failed()).isZero();
        List<Track> changed = lib.catalog.current().subtreeTracks(album).stream()
                .filter(t -> "Bulk Genre".equals(t.tags().genre())).toList();
        assertThat(changed).hasSize(n);
        List<String> log = readLog();
        assertThat(log).hasSize(n);
        assertThat(log.get(0)).contains("\"was\"").contains("\"now\"").contains("Bulk Genre");
    }

    private List<String> readLog() throws IOException {
        return Files.readAllLines(tmp.resolve("data/tag-changes.log"));
    }
}
