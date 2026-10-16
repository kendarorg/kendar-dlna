package org.kendar.dlna.catalog;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The rules a set of configured navigations and extra tags must follow (config start-up and live edits alike). */
public final class NavigationRules {

    private NavigationRules() {
    }

    /**
     * Navigation names are unique, every level is a known tag, a virtual tag or one of {@code extraTags}, the
     * thresholds are sane. The index tags are returned in their canonical spelling.
     *
     * @throws IllegalArgumentException with a message naming the problem
     */
    public static List<Navigation> validate(List<Navigation> navigations, List<String> extraTags) {
        Set<String> extra = new LinkedHashSet<>();
        for (String t : extraTags) {
            if (t == null || t.isBlank()) {
                throw new IllegalArgumentException("extraTags entries must not be blank");
            }
            if (!extra.add(t.strip().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("extraTags: duplicate tag '" + t + "'");
            }
        }
        Set<String> names = new HashSet<>();
        java.util.ArrayList<Navigation> out = new java.util.ArrayList<>();
        for (Navigation n : navigations) {
            if (n == null || n.name().isBlank()) {
                throw new IllegalArgumentException("navigations: every navigation needs a name");
            }
            if (!names.add(n.name().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("navigations: duplicate name '" + n.name() + "'");
            }
            if (n.index().isEmpty()) {
                throw new IllegalArgumentException("navigations['" + n.name() + "']: index needs at least one tag");
            }
            List<String> index = new java.util.ArrayList<>();
            for (String tag : n.index()) {
                index.add(TagNames.canonical(tag, extraTags).orElseThrow(() -> new IllegalArgumentException(
                        "navigations['" + n.name() + "']: unknown tag '" + tag + "' (known: "
                                + TagNames.all(extraTags) + ")")));
            }
            if (n.subitems() != null && n.subitems() < 1) {
                throw new IllegalArgumentException("navigations['" + n.name() + "']: subitems must be at least 1");
            }
            if (n.ranges() != null && n.ranges() < 2) {
                throw new IllegalArgumentException("navigations['" + n.name() + "']: ranges must be at least 2");
            }
            if (n.random() != null && n.random() > 1000) {
                throw new IllegalArgumentException("navigations['" + n.name() + "']: random must be at most 1000");
            }
            out.add(new Navigation(n.name(), n.subitems(), n.ranges(), index, n.random()));
        }
        return List.copyOf(out);
    }
}
