package org.kendar.dlna.catalog;

import java.util.List;

/**
 * Parsed {@code settings.json} of one directory (its own values; see {@link #inheritFrom} for what a
 * sub-directory takes from its parent). {@code ignore} holds gitignore-like patterns ({@link IgnoreRules}) hiding
 * entries below the directory from everybody but the admin. {@code recent}
 * is N of the "Recently added" container (0 = disabled). {@code displayName} (nullable, trimmed, never blank)
 * replaces the directory name in every listing; the directory on disk is not renamed. {@code bucketThreshold}
 * (nullable = use {@code views.bucketThreshold} of the config) is the list length above which the Artists and
 * Titles views of this directory are split into A-Z / # containers. {@code hidden} keeps the directory and everything
 * below it out of every listing, search, shuffle and view (the admin still sees it); it stays addressable by path.
 */
public record DirectorySettings(SortMode sort, List<String> order, AlbumInfo album, int random,
        List<ViewKind> views, int recent, String displayName, boolean keepWholeFiles, Integer bucketThreshold,
        boolean hidden, List<String> ignore) {

    public static final DirectorySettings DEFAULT =
            new DirectorySettings(SortMode.FILENAME, List.of(), AlbumInfo.NONE, 0, List.of(), 0, null, false, null);

    /** Settings without hide patterns. */
    public DirectorySettings(SortMode sort, List<String> order, AlbumInfo album, int random,
            List<ViewKind> views, int recent, String displayName, boolean keepWholeFiles, Integer bucketThreshold,
            boolean hidden) {
        this(sort, order, album, random, views, recent, displayName, keepWholeFiles, bucketThreshold, hidden,
                List.of());
    }

    /** Settings of a shown directory ({@code hidden} false). */
    public DirectorySettings(SortMode sort, List<String> order, AlbumInfo album, int random,
            List<ViewKind> views, int recent, String displayName, boolean keepWholeFiles, Integer bucketThreshold) {
        this(sort, order, album, random, views, recent, displayName, keepWholeFiles, bucketThreshold, false);
    }

    /** Settings without a bucket threshold of their own. */
    public DirectorySettings(SortMode sort, List<String> order, AlbumInfo album, int random,
            List<ViewKind> views, int recent, String displayName, boolean keepWholeFiles) {
        this(sort, order, album, random, views, recent, displayName, keepWholeFiles, null);
    }

    /** Settings with the default {@code keepWholeFiles} (false). */
    public DirectorySettings(SortMode sort, List<String> order, AlbumInfo album, int random,
            List<ViewKind> views, int recent, String displayName) {
        this(sort, order, album, random, views, recent, displayName, false);
    }

    public DirectorySettings {
        sort = sort == null ? SortMode.FILENAME : sort;
        order = order == null ? List.of() : List.copyOf(order);
        album = album == null ? AlbumInfo.NONE : album;
        random = Math.max(0, random);
        views = views == null ? List.of() : List.copyOf(views);
        recent = Math.max(0, recent);
        displayName = displayName == null || displayName.isBlank() ? null : displayName.strip();
        bucketThreshold = bucketThreshold == null || bucketThreshold < 1 ? null : bucketThreshold;
        ignore = ignore == null ? List.of() : ignore.stream().filter(x -> x != null && !x.isBlank())
                .map(String::strip).toList();
    }

    /**
     * The settings in force for a sub-directory of {@code parent} (itself already effective): the inheritable
     * values - sort mode, keepWholeFiles, bucketThreshold - that this directory leaves at their defaults are taken
     * from the parent. Everything identifying the directory (display name, album fields, order, hide patterns) and
     * the settings that create virtual containers (random, recent, views) stay its own.
     */
    public DirectorySettings inheritFrom(DirectorySettings parent) {
        SortMode s = sort == SortMode.FILENAME && parent.sort != SortMode.EXPLICIT ? parent.sort : sort;
        boolean keep = keepWholeFiles || parent.keepWholeFiles;
        Integer bucket = bucketThreshold != null ? bucketThreshold : parent.bucketThreshold;
        if (s == sort && keep == keepWholeFiles && java.util.Objects.equals(bucket, bucketThreshold)) {
            return this;
        }
        return new DirectorySettings(s, order, album, random, views, recent, displayName, keep, bucket, hidden,
                ignore);
    }

    /** Settings without a display name. */
    public DirectorySettings(SortMode sort, List<String> order, AlbumInfo album, int random,
            List<ViewKind> views, int recent) {
        this(sort, order, album, random, views, recent, null);
    }

    /** Settings without a Recently added container. */
    public DirectorySettings(SortMode sort, List<String> order, AlbumInfo album, int random,
            List<ViewKind> views) {
        this(sort, order, album, random, views, 0, null);
    }
}
