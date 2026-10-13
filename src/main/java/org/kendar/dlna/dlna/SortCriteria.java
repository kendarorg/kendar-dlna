package org.kendar.dlna.dlna;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The ContentDirectory {@code SortCriteria} argument: a comma separated list of {@code +property} / {@code -property}.
 * Properties the server cannot sort by are ignored (the order of the others stays); a malformed list is an error
 * (UPnP 709).
 */
public final class SortCriteria {

    /** What GetSortCapabilities advertises. */
    public static final List<String> CAPABILITIES = List.of("dc:title", "dc:creator", "upnp:artist", "upnp:album",
            "upnp:genre", "upnp:originalTrackNumber", "dc:date", "upnp:class");

    private static final Set<String> SUPPORTED = Set.copyOf(CAPABILITIES);

    /** One sort key. */
    public record Key(String property, boolean ascending) {
    }

    /** The argument is not a valid sort criteria list. */
    public static final class InvalidSortCriteriaException extends Exception {
        private static final long serialVersionUID = 1L;

        InvalidSortCriteriaException(String message) {
            super(message);
        }
    }

    private SortCriteria() {
    }

    /** The usable keys in order; empty for a blank argument. */
    public static List<Key> parse(String criteria) throws InvalidSortCriteriaException {
        List<Key> out = new ArrayList<>();
        if (criteria == null || criteria.isBlank()) {
            return out;
        }
        for (String part : criteria.split(",", -1)) {
            String p = part.strip();
            if (p.length() < 2 || (p.charAt(0) != '+' && p.charAt(0) != '-')) {
                throw new InvalidSortCriteriaException("Invalid sort criterion: '" + part + "'");
            }
            String property = p.substring(1);
            if (!property.matches("[A-Za-z][A-Za-z0-9]*(:[A-Za-z][A-Za-z0-9]*)?(@[A-Za-z0-9:]+)?")) {
                throw new InvalidSortCriteriaException("Invalid sort property: '" + property + "'");
            }
            if (SUPPORTED.contains(property)) {
                out.add(new Key(property, p.charAt(0) == '+'));
            }
        }
        return out;
    }
}
