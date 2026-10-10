package org.kendar.dlna.dlna;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.kendar.dlna.search.Field;
import org.kendar.dlna.search.Kind;
import org.kendar.dlna.search.Query;

/**
 * Hand-written recursive-descent parser for UPnP ContentDirectory search criteria, producing the DLNA-independent
 * {@link Query}.
 *
 * <pre>
 * criteria := '*' | or
 * or       := and ( 'or' and )*
 * and      := primary ( 'and' primary )*          -- 'and' binds tighter than 'or'
 * primary  := '(' or ')' | property op value
 * op       := contains | doesNotContain | = | != | derivedfrom | exists
 * value    := '"' text '"'   (backslash escapes a quote or a backslash)   |   true|false after 'exists'
 * </pre>
 *
 * Supported properties: dc:title, upnp:artist, dc:creator, upnp:album, upnp:genre, upnp:author (composer), upnp:class (plus
 * upnp:albumArtist). Well-formed criteria on any other property are accepted and treated as "property absent"
 * (never match, {@code exists false} matches everything), so clients sending e.g. {@code @refID exists false}
 * work. Ordering operators ({@code < <= > >=}) are rejected. Keywords are case-insensitive.
 */
public final class SearchCriteriaParser {

    private static final int MAX_DEPTH = 50;
    private static final Pattern PROPERTY = Pattern.compile("[A-Za-z@][A-Za-z0-9_:.@\\-]*");

    private static final Map<String, Field> FIELDS = Map.of(
            "dc:title", Field.TITLE,
            "upnp:artist", Field.ARTIST,
            "dc:creator", Field.ARTIST,
            "upnp:album", Field.ALBUM,
            "upnp:genre", Field.GENRE,
            "upnp:albumartist", Field.ALBUM_ARTIST,
            "upnp:author", Field.COMPOSER,
            "upnp:composer", Field.COMPOSER);

    private enum T { LPAREN, RPAREN, WORD, SYMBOL, STRING, END }

    private record Token(T type, String text) {
    }

    private final List<Token> tokens;
    private int pos;
    private int depth;

    private SearchCriteriaParser(List<Token> tokens) {
        this.tokens = tokens;
    }

    /** @throws InvalidSearchCriteriaException for anything that is not valid criteria (never any other exception) */
    public static Query parse(String criteria) throws InvalidSearchCriteriaException {
        if (criteria == null || criteria.isBlank()) {
            throw new InvalidSearchCriteriaException("Empty search criteria");
        }
        if (criteria.strip().equals("*")) {
            return new Query.All();
        }
        SearchCriteriaParser p = new SearchCriteriaParser(tokenize(criteria));
        Query q = p.or();
        if (p.peek().type() != T.END) {
            throw new InvalidSearchCriteriaException("Unexpected '" + p.peek().text() + "'");
        }
        return q;
    }

    // ---- lexer ----

    private static List<Token> tokenize(String s) throws InvalidSearchCriteriaException {
        List<Token> out = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '(') {
                out.add(new Token(T.LPAREN, "("));
                i++;
            } else if (c == ')') {
                out.add(new Token(T.RPAREN, ")"));
                i++;
            } else if (c == '"') {
                StringBuilder sb = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < n) {
                    char d = s.charAt(i++);
                    if (d == '\\') {
                        if (i >= n) {
                            throw new InvalidSearchCriteriaException("Dangling escape in string");
                        }
                        sb.append(s.charAt(i++));
                    } else if (d == '"') {
                        closed = true;
                        break;
                    } else {
                        sb.append(d);
                    }
                }
                if (!closed) {
                    throw new InvalidSearchCriteriaException("Unterminated string");
                }
                out.add(new Token(T.STRING, sb.toString()));
            } else if (c == '=' || c == '!' || c == '<' || c == '>') {
                int start = i++;
                if (i < n && s.charAt(i) == '=') {
                    i++;
                }
                String sym = s.substring(start, i);
                if (sym.equals("!")) {
                    throw new InvalidSearchCriteriaException("Unexpected '!'");
                }
                out.add(new Token(T.SYMBOL, sym));
            } else {
                int start = i;
                while (i < n && !Character.isWhitespace(s.charAt(i)) && "()\"=!<>".indexOf(s.charAt(i)) < 0) {
                    i++;
                }
                out.add(new Token(T.WORD, s.substring(start, i)));
            }
        }
        out.add(new Token(T.END, "<end>"));
        return out;
    }

    // ---- parser ----

    private Token peek() {
        return tokens.get(pos);
    }

    private Token next() {
        Token t = tokens.get(pos);
        if (t.type() != T.END) {
            pos++;
        }
        return t;
    }

    private boolean isKeyword(String kw) {
        Token t = peek();
        return t.type() == T.WORD && t.text().equalsIgnoreCase(kw);
    }

    private Query or() throws InvalidSearchCriteriaException {
        List<Query> parts = new ArrayList<>();
        parts.add(and());
        while (isKeyword("or")) {
            next();
            parts.add(and());
        }
        return parts.size() == 1 ? parts.get(0) : new Query.Or(parts);
    }

    private Query and() throws InvalidSearchCriteriaException {
        List<Query> parts = new ArrayList<>();
        parts.add(primary());
        while (isKeyword("and")) {
            next();
            parts.add(primary());
        }
        return parts.size() == 1 ? parts.get(0) : new Query.And(parts);
    }

    private Query primary() throws InvalidSearchCriteriaException {
        Token t = next();
        if (t.type() == T.LPAREN) {
            if (++depth > MAX_DEPTH) {
                throw new InvalidSearchCriteriaException("Criteria nested too deeply");
            }
            Query q = or();
            if (next().type() != T.RPAREN) {
                throw new InvalidSearchCriteriaException("Missing ')'");
            }
            depth--;
            return q;
        }
        if (t.type() != T.WORD || !PROPERTY.matcher(t.text()).matches() || isReserved(t.text())) {
            throw new InvalidSearchCriteriaException("Expected a property but found '" + t.text() + "'");
        }
        String property = t.text().toLowerCase(Locale.ROOT);
        Token opToken = next();
        if (opToken.type() != T.WORD && opToken.type() != T.SYMBOL) {
            throw new InvalidSearchCriteriaException("Expected an operator after " + t.text());
        }
        String op = opToken.type() == T.SYMBOL ? opToken.text() : opToken.text().toLowerCase(Locale.ROOT);
        if (op.equals("exists")) {
            Token v = next();
            if (v.type() == T.END || v.type() == T.LPAREN || v.type() == T.RPAREN
                    || !(v.text().equalsIgnoreCase("true") || v.text().equalsIgnoreCase("false"))) {
                throw new InvalidSearchCriteriaException("exists needs true or false");
            }
            return exists(property, v.text().equalsIgnoreCase("true"));
        }
        if (!Set.of("contains", "doesnotcontain", "=", "!=", "derivedfrom").contains(op)) {
            throw new InvalidSearchCriteriaException("Unsupported operator '" + opToken.text() + "'");
        }
        Token value = next();
        if (value.type() != T.STRING) {
            throw new InvalidSearchCriteriaException("Expected a quoted value after '" + opToken.text() + "'");
        }
        return compare(property, op, value.text());
    }

    private static boolean isReserved(String word) {
        return word.equalsIgnoreCase("and") || word.equalsIgnoreCase("or");
    }

    private static Query exists(String property, boolean wanted) {
        if (property.equals("upnp:class")) {
            return wanted ? new Query.Kinds(EnumSet.allOf(Kind.class)) : new Query.None();
        }
        Field f = FIELDS.get(property);
        if (f == null) {
            return wanted ? new Query.None() : new Query.All();
        }
        return new Query.Exists(f, wanted);
    }

    private static Query compare(String property, String op, String value) {
        if (property.equals("upnp:class")) {
            return classQuery(op, value);
        }
        Field f = FIELDS.get(property);
        if (f == null) {
            return new Query.None();
        }
        Query.Op o = switch (op) {
            case "contains" -> Query.Op.CONTAINS;
            case "doesnotcontain" -> Query.Op.NOT_CONTAINS;
            case "=" -> Query.Op.EQUALS;
            case "!=" -> Query.Op.NOT_EQUALS;
            default -> Query.Op.STARTS_WITH;   // derivedfrom
        };
        return new Query.Term(f, o, value);
    }

    private static Query classQuery(String op, String value) {
        Predicate<String> matches = switch (op) {
            case "=", "!=" -> c -> c.equals(value);
            case "contains", "doesnotcontain" -> c -> c.contains(value);
            default -> c -> c.equals(value) || c.startsWith(value + ".");   // derivedfrom
        };
        boolean negate = op.equals("!=") || op.equals("doesnotcontain");
        Set<Kind> kinds = EnumSet.noneOf(Kind.class);
        for (Kind k : Kind.values()) {
            if (matches.test(k.upnpClass()) != negate) {
                kinds.add(k);
            }
        }
        return new Query.Kinds(kinds);
    }
}
