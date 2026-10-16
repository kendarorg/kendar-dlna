package org.kendar.dlna.catalog;

import java.util.List;

/**
 * A configured tag navigation (config.json {@code navigations}): the library (or a directory's subtree) grouped by
 * the tags of {@code index}, one container level per tag, tracks at the end. {@code subitems} and {@code ranges}
 * override the global defaults for this navigation (null = use them): a level with more than {@code subitems}
 * entries is split into at most {@code ranges} containers.
 */
public record Navigation(String name, Integer subitems, Integer ranges, List<String> index) {

    public Navigation {
        name = name == null ? "" : name.strip();
        index = index == null ? List.of() : index.stream().filter(x -> x != null && !x.isBlank()).map(String::strip)
                .toList();
        subitems = subitems == null ? null : subitems;
        ranges = ranges == null ? null : ranges;
    }

    /** The stable id fragment of this navigation inside container ids ({@code v.<dir>.<fragment>...}). */
    public String key() {
        return "n" + org.kendar.dlna.shared.StableIds.id("nav", name.toLowerCase(java.util.Locale.ROOT));
    }
}
