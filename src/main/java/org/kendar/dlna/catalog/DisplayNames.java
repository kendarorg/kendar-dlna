package org.kendar.dlna.catalog;

/**
 * Display-name rules: folder = settings displayName (verbatim) else album title else directory name, followed by " (CD n)" when the directory's
 * settings.json sets a disc number; track = tag title else file name.
 */
public final class DisplayNames {

    private DisplayNames() {
    }

    public static String folderTitle(Folder folder) {
        String custom = folder.settings().displayName();
        if (custom != null) {
            return custom;   // verbatim: the user chose that exact text, no disc suffix
        }
        AlbumInfo album = folder.settings().album();
        String base = album.title() != null ? album.title() : folder.ref().name();
        Integer disc = album.discNumber();
        if (disc == null || mentionsDisc(base, disc)) {
            return base;
        }
        return base + " (CD " + disc + ")";
    }

    /** True when the name already says "CD 2" / "Disc 2" / "Disk02" for this disc, so it is not repeated. */
    private static boolean mentionsDisc(String name, int disc) {
        return java.util.regex.Pattern
                .compile("(?<![\\p{L}\\p{N}])(cd|dis[ck])[\\s._-]*0*" + disc + "(?![\\p{N}])",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(name).find();
    }

    public static String trackTitle(Track track) {
        String t = track.tags().title();
        if (t != null && !t.isBlank()) {
            return t.strip();
        }
        String name = track.fileName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
