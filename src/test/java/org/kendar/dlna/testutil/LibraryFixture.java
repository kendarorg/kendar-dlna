package org.kendar.dlna.testutil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.kendar.dlna.catalog.CatalogStore;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.SnapshotHolder;
import org.kendar.dlna.curation.LibraryFiles;
import org.kendar.dlna.ingestion.CatalogService;
import org.kendar.dlna.ingestion.CompositeTagReader;
import org.kendar.dlna.ingestion.JAudioTaggerReader;
import org.kendar.dlna.ingestion.LibraryScanner;
import org.kendar.dlna.ingestion.MidiTagReader;
import org.kendar.dlna.ingestion.OpusTagReader;
import org.kendar.dlna.ingestion.RescanService;
import org.kendar.dlna.ingestion.TagReader;
import org.kendar.dlna.shared.RootRef;

/**
 * A real library on disk (generated and copied audio files) scanned with the real tag reader into a
 * {@link CatalogService} backed by an in-memory store. Layout: {@code lib/Album/01 one.wav, 02 two.flac,
 * 03 three.mp3, 04 four.mid} and an empty {@code lib/Other}.
 */
public final class LibraryFixture {

    /** Counts saves of the persisted index. */
    public static final class MemoryStore implements CatalogStore {
        public Snapshot saved;
        public int saves;

        @Override
        public Optional<Snapshot> load() {
            return Optional.ofNullable(saved);
        }

        @Override
        public void save(Snapshot snapshot) {
            saved = snapshot;
            saves++;
        }
    }

    public final Path music;
    public final Path playlists;
    public final Map<String, Path> roots = new LinkedHashMap<>();
    public final MemoryStore store = new MemoryStore();
    public final MutableClock clock = new MutableClock();
    public final TagReader reader = new CompositeTagReader(new JAudioTaggerReader(), new OpusTagReader(),
            new MidiTagReader(true, ""));
    public final LibraryScanner scanner = new LibraryScanner(reader, clock);
    public final CatalogService catalog;
    public final RescanService rescans;
    public final LibraryFiles files;

    public LibraryFixture(Path tmp) {
        try {
            music = tmp.resolve("music");
            playlists = tmp.resolve("playlists");
            Path album = Files.createDirectories(music.resolve("Album"));
            Files.createDirectories(music.resolve("Other"));
            AudioFiles.fixture("sweep.wav", album.resolve("01 one.wav"));
            AudioFiles.fixture("sweep.flac", album.resolve("02 two.flac"));
            AudioFiles.fixture("sweep.mp3", album.resolve("03 three.mp3"));
            AudioFiles.midi(album.resolve("04 four.mid"), "Four", 2);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        roots.put("main", music);
        catalog = new CatalogService(new SnapshotHolder(), store, clock);
        catalog.install(scanner.scan(roots, playlists, 1), true);
        store.saves = 0;
        rescans = new RescanService(scanner, catalog, roots, playlists, clock);
        files = ref -> {
            Path root = roots.get(ref.root());
            return root == null ? null : root.resolve(ref.rel()).normalize();
        };
    }

    public static RootRef ref(String qualified) {
        return RootRef.parse(qualified);
    }

    public Path file(String qualified) {
        return files.resolve(ref(qualified));
    }
}
