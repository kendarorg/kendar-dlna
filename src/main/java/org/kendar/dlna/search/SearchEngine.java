package org.kendar.dlna.search;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.kendar.dlna.catalog.Child;
import org.kendar.dlna.catalog.DirectorySettings;
import org.kendar.dlna.catalog.DisplayNames;
import org.kendar.dlna.catalog.EffectiveTrack;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.shared.Normalizer;
import org.kendar.dlna.shared.RootRef;

/**
 * In-memory linear scan of a catalog snapshot (plan section 9). Independent of DLNA.
 * <p>
 * Results come in display order (depth first, a directory before its content), so paging is stable for a given
 * snapshot. The album overlay is applied to album-level fields. Only real catalog entries are searched, hence
 * Random N and view containers (and their items, which would be duplicates) can never appear.
 */
public final class SearchEngine {

    private static final List<Field> CONCRETE = Arrays.stream(Field.values()).filter(f -> f != Field.ANY).toList();

    private SearchEngine() {
    }

    /** @return hits in display order, empty when the scope directory is unknown */
    public static List<SearchHit> search(Snapshot snap, SearchScope scope, Query query) {
        List<SearchHit> out = new ArrayList<>();
        if (scope.folder() == null) {
            for (Folder root : snap.roots()) {
                if (!scope.allows(root.ref().root())) {
                    continue;
                }
                visit(snap, root, query, out);
            }
        } else if (scope.allows(scope.folder().root()) && snap.folder(scope.folder()).isPresent()) {
            walk(snap, scope.folder(), query, out);
        }
        return out;
    }

    private static void visit(Snapshot snap, Folder folder, Query q, List<SearchHit> out) {
        add(snap, new SearchHit.FolderHit(folder), q, out);
        walk(snap, folder.ref(), q, out);
    }

    private static void walk(Snapshot snap, RootRef folder, Query q, List<SearchHit> out) {
        for (Child c : snap.children(folder)) {
            switch (c) {
                case Child.FolderChild f -> visit(snap, f.folder(), q, out);
                case Child.TrackChild t -> add(snap, new SearchHit.TrackHit(t.track()), q, out);
            }
        }
    }

    private static void add(Snapshot snap, SearchHit hit, Query q, List<SearchHit> out) {
        if (eval(q, candidate(snap, hit))) {
            out.add(hit);
        }
    }

    /** Raw field values of one candidate. */
    private record Candidate(Kind kind, Function<Field, List<String>> values) {
    }

    private static Candidate candidate(Snapshot snap, SearchHit hit) {
        return switch (hit) {
            case SearchHit.TrackHit h -> {
                Track t = h.track();
                Optional<Folder> parent = snap.folder(t.parentRef());
                EffectiveTrack et = EffectiveTrack.of(t,
                        parent.map(Folder::settings).orElse(DirectorySettings.DEFAULT));
                yield new Candidate(Kind.TRACK, f -> switch (f) {
                    case TITLE -> listOf(t.tags().title(), baseName(t.fileName()));
                    case ARTIST -> listOf(t.tags().artist(), et.albumArtist());
                    case ALBUM -> listOf(et.album());
                    case ALBUM_ARTIST -> listOf(et.albumArtist());
                    case GENRE -> listOf(et.genre());
                    case COMPOSER -> t.tags().allComposers();
                    case FILENAME -> listOf(t.fileName());
                    case DIRECTORY -> listOf(t.parentRef().name());
                    case ANY -> List.of();
                });
            }
            case SearchHit.FolderHit h -> {
                Folder fo = h.folder();
                yield new Candidate(Kind.FOLDER, f -> switch (f) {
                    case TITLE -> listOf(DisplayNames.folderTitle(fo), fo.ref().name(),
                            fo.settings().displayName(), fo.settings().album().title());
                    case DIRECTORY -> listOf(fo.ref().name());
                    default -> List.of();
                });
            }
        };
    }

    private static boolean eval(Query q, Candidate c) {
        return switch (q) {
            case Query.All a -> true;
            case Query.None n -> false;
            case Query.And a -> a.parts().stream().allMatch(p -> eval(p, c));
            case Query.Or o -> o.parts().stream().anyMatch(p -> eval(p, c));
            case Query.Kinds k -> k.kinds().contains(c.kind());
            case Query.Exists e -> e.present() == fields(e.field()).stream()
                    .anyMatch(f -> !c.values().apply(f).isEmpty());
            case Query.Term t -> term(t, c);
        };
    }

    private static List<Field> fields(Field f) {
        return f == Field.ANY ? CONCRETE : List.of(f);
    }

    private static boolean term(Query.Term t, Candidate c) {
        String needle = Normalizer.normalize(t.value());
        List<String> values = new ArrayList<>();
        for (Field f : fields(t.field())) {
            for (String v : c.values().apply(f)) {
                values.add(Normalizer.normalize(v));
            }
        }
        return switch (t.op()) {
            case CONTAINS -> values.stream().anyMatch(v -> v.contains(needle));
            case EQUALS -> values.stream().anyMatch(v -> v.equals(needle));
            case STARTS_WITH -> values.stream().anyMatch(v -> v.startsWith(needle));
            case NOT_CONTAINS -> !values.isEmpty() && values.stream().noneMatch(v -> v.contains(needle));
            case NOT_EQUALS -> !values.isEmpty() && values.stream().noneMatch(v -> v.equals(needle));
        };
    }

    private static List<String> listOf(String... values) {
        List<String> out = new ArrayList<>(2);
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                out.add(v);
            }
        }
        return out;
    }

    private static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
