package org.kendar.dlna.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * gitignore-like hide patterns of a directory's {@code settings.json} ({@code ignore}). A pattern applies to the
 * directory that declares it and everything below. Supported: {@code *}, {@code ?}, {@code **}, a leading
 * {@code /} or any inner {@code /} (anchored to the declaring directory, otherwise the pattern matches the name at
 * any depth), a trailing {@code /} (directories only), {@code !} (re-include) and {@code #} comments. The last
 * matching pattern wins; a hidden directory hides all of its content.
 */
public final class IgnoreRules {

    private record Rule(Pattern regex, boolean dirOnly, boolean negated) {
    }

    private final List<Rule> rules;

    public IgnoreRules(List<String> patterns) {
        List<Rule> out = new ArrayList<>();
        for (String raw : patterns) {
            Rule r = compile(raw);
            if (r != null) {
                out.add(r);
            }
        }
        this.rules = List.copyOf(out);
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /** True / false when a pattern hides / re-includes the entry, null when none matches. */
    public Boolean match(String rel, boolean isDir) {
        Boolean state = null;
        for (Rule r : rules) {
            if ((!r.dirOnly() || isDir) && r.regex().matcher(rel).matches()) {
                state = !r.negated();
            }
        }
        return state;
    }

    private static Rule compile(String raw) {
        String p = raw == null ? "" : raw.strip();
        if (p.isEmpty() || p.startsWith("#")) {
            return null;
        }
        boolean negated = p.startsWith("!");
        if (negated) {
            p = p.substring(1);
        }
        boolean dirOnly = p.endsWith("/");
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        boolean anchored = p.indexOf('/') >= 0;
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.isEmpty()) {
            return null;
        }
        StringBuilder re = new StringBuilder(anchored ? "" : "(?:.*/)?");
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '*') {
                if (i + 1 < p.length() && p.charAt(i + 1) == '*') {
                    i++;
                    if (i + 1 < p.length() && p.charAt(i + 1) == '/') {
                        i++;
                        re.append("(?:.*/)?");
                    } else {
                        re.append(".*");
                    }
                } else {
                    re.append("[^/]*");
                }
            } else if (c == '?') {
                re.append("[^/]");
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return new Rule(Pattern.compile(re.toString()), dirOnly, negated);
    }
}
