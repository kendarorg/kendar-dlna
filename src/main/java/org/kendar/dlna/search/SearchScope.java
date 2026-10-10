package org.kendar.dlna.search;

import java.util.Set;

import org.kendar.dlna.shared.RootRef;

/**
 * Where to search: the whole library or the subtree below one directory (the directory itself excluded),
 * optionally restricted to a set of library roots ({@code roots == null}: every root). The roots restriction is how
 * the web API confines a user to the allowed roots.
 */
public record SearchScope(RootRef folder, Set<String> roots) {

    public static final SearchScope LIBRARY = new SearchScope(null, null);

    public SearchScope {
        roots = roots == null ? null : Set.copyOf(roots);
    }

    public SearchScope(RootRef folder) {
        this(folder, null);
    }

    public static SearchScope below(RootRef folder) {
        return new SearchScope(folder, null);
    }

    /** The whole library of a set of roots. */
    public static SearchScope ofRoots(Set<String> roots) {
        return new SearchScope(null, roots);
    }

    /** The subtree below a directory, if its root is among {@code roots}. */
    public static SearchScope below(RootRef folder, Set<String> roots) {
        return new SearchScope(folder, roots);
    }

    public boolean allows(String root) {
        return roots == null || roots.contains(root);
    }
}
