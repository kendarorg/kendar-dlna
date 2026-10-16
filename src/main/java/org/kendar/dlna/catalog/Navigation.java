package org.kendar.dlna.catalog;

import java.util.List;

/**
 * A configured tag navigation (config.json {@code navigations}): the library (or a directory's subtree) grouped by
 * the tags of {@code index}, one container level per tag, tracks at the end. {@code subitems} and {@code ranges}
 * override the global defaults for this navigation (null = use them): a level with more than {@code subitems}
 * entries is split into at most {@code ranges} containers. {@code random} (null or 0 = none) is the quantity of
 * the "Random N" pick offered with the navigation: N random tracks of the part of the library it is showing.
 */
public record Navigation(String name, Integer subitems, Integer ranges, List<String> index, Integer random) {

    /** Without a Random N. */
    public Navigation(String name, Integer subitems, Integer ranges, List<String> index) {
        this(name, subitems, ranges, index, null);
    }

    public Navigation {
        name = name == null ? "" : name.strip();
        index = index == null ? List.of() : index.stream().filter(x -> x != null && !x.isBlank()).map(String::strip)
                .toList();
        subitems = subitems == null ? null : subitems;
        ranges = ranges == null ? null : ranges;
        random = random == null || random <= 0 ? null : random;
    }

    /** The N of Random N, 0 when the navigation has none. */
    public int randomCount() {
        return random == null ? 0 : random;
    }

    /** The stable id fragment of this navigation inside container ids ({@code v.<dir>.<fragment>...}). */
    public String key() {
        return "n" + org.kendar.dlna.shared.StableIds.id("nav", name.toLowerCase(java.util.Locale.ROOT));
    }
}
