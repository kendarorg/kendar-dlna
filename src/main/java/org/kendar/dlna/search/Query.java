package org.kendar.dlna.search;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.kendar.dlna.shared.Normalizer;

/** Internal, DLNA-independent query model produced by the UPnP parser or the web UI. */
public sealed interface Query {

    /** Comparison operators; values are compared normalized (case, accents, whitespace). */
    enum Op {
        CONTAINS, NOT_CONTAINS, EQUALS, NOT_EQUALS, STARTS_WITH
    }

    /** Matches everything. */
    record All() implements Query {
    }

    /** Matches nothing. */
    record None() implements Query {
    }

    record And(List<Query> parts) implements Query {
        public And {
            parts = List.copyOf(parts);
        }
    }

    record Or(List<Query> parts) implements Query {
        public Or {
            parts = List.copyOf(parts);
        }
    }

    /**
     * Field comparison. Negative operators need the field to be present on the result (a folder has no artist,
     * so {@code artist != x} does not match folders).
     */
    record Term(Field field, Op op, String value) implements Query {
        public Term {
            value = value == null ? "" : value;
        }
    }

    /** Field present ({@code present = true}) or absent. */
    record Exists(Field field, boolean present) implements Query {
    }

    /** Restricts the kind of result. */
    record Kinds(Set<Kind> kinds) implements Query {
        public Kinds {
            kinds = kinds.isEmpty() ? EnumSet.noneOf(Kind.class) : EnumSet.copyOf(kinds);
        }
    }

    /** Free-text query for the web UI: every word must be contained in some field. Blank text gives {@link All}. */
    static Query text(String text) {
        List<Query> words = Arrays.stream(Normalizer.normalize(text).split(" "))
                .filter(w -> !w.isEmpty())
                .<Query>map(w -> new Term(Field.ANY, Op.CONTAINS, w)).toList();
        return words.isEmpty() ? new All() : new And(words);
    }
}
