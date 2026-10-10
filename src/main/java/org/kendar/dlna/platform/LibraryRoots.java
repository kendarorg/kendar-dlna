package org.kendar.dlna.platform;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.kendar.dlna.artwork.ArtFiles;
import org.kendar.dlna.delivery.MediaFiles;
import org.kendar.dlna.shared.RootRef;

/** Configured library roots (name to directory, in configured order); resolves catalog locations to files. */
final class LibraryRoots implements MediaFiles, ArtFiles, org.kendar.dlna.curation.LibraryFiles {

    private final Map<String, Path> roots;

    LibraryRoots(Map<String, String> configured) {
        Map<String, Path> m = new LinkedHashMap<>();
        configured.forEach((name, dir) -> m.put(name, Path.of(dir).toAbsolutePath().normalize()));
        this.roots = Collections.unmodifiableMap(m);
    }

    /** Roots in configured order. */
    Map<String, Path> roots() {
        return roots;
    }

    private static final java.util.regex.Pattern VIRTUAL = java.util.regex.Pattern.compile("^(.*\\.[^./]+)#\\d+$");

    /** A virtual track ref {@code dir/file.flac#3} lives in its source file {@code dir/file.flac}. */
    static String sourceRel(String rel) {
        java.util.regex.Matcher m = VIRTUAL.matcher(rel);
        return m.matches() ? m.group(1) : rel;
    }

    @Override
    public Path resolve(RootRef ref) {
        Path root = roots.get(ref.root());
        if (root == null) {
            return null;
        }
        Path p = root.resolve(sourceRel(ref.rel())).normalize();
        return p.startsWith(root) ? p : null;
    }
}
