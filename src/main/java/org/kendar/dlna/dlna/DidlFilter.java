package org.kendar.dlna.dlna;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The ContentDirectory {@code Filter} argument. A blank filter or {@code *} selects everything (many control
 * points send an empty filter and expect full metadata). Required properties (id, parentID, restricted,
 * dc:title, upnp:class) are always emitted regardless of the filter; the callers only consult this class for
 * the optional ones.
 */
public final class DidlFilter {

    public static final DidlFilter ALL = new DidlFilter(true, Set.of());

    private final boolean all;
    private final Set<String> tokens;

    private DidlFilter(boolean all, Set<String> tokens) {
        this.all = all;
        this.tokens = tokens;
    }

    public static DidlFilter parse(String filter) {
        if (filter == null || filter.isBlank()) {
            return ALL;
        }
        Set<String> t = new HashSet<>();
        for (String s : filter.split(",")) {
            String v = s.strip().toLowerCase(Locale.ROOT);
            if (v.equals("*")) {
                return ALL;
            }
            if (!v.isEmpty()) {
                t.add(v);
            }
        }
        return new DidlFilter(false, Set.copyOf(t));
    }

    /** Whether an element/property such as {@code upnp:artist} or {@code res} was requested. */
    public boolean wants(String property) {
        if (all) {
            return true;
        }
        String p = property.toLowerCase(Locale.ROOT);
        if (tokens.contains(p)) {
            return true;
        }
        // asking for any attribute of a property implies the property itself
        String prefix = p + "@";
        return tokens.stream().anyMatch(s -> s.startsWith(prefix));
    }

    /**
     * Whether an attribute such as {@code res@duration} is wanted: requested explicitly, or implied by
     * requesting the owning property without attribute qualifiers (pragmatic: control points asking for
     * {@code res} expect size and duration).
     */
    public boolean wantsAttribute(String property, String attribute) {
        if (all) {
            return true;
        }
        String p = property.toLowerCase(Locale.ROOT);
        return tokens.contains(p) || tokens.contains(p + "@" + attribute.toLowerCase(Locale.ROOT));
    }

    /** Container attributes are written {@code @childCount} (or {@code container@childCount}). */
    public boolean wantsContainerAttribute(String attribute) {
        if (all) {
            return true;
        }
        String a = attribute.toLowerCase(Locale.ROOT);
        return tokens.contains("@" + a) || tokens.contains("container@" + a);
    }
}
