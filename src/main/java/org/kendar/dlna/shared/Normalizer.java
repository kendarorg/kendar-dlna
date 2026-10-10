package org.kendar.dlna.shared;

import java.text.Normalizer.Form;
import java.util.Comparator;
import java.util.Locale;
import java.util.regex.Pattern;

/** Text normalization (case, accents, whitespace) and natural-number ordering, shared by sorting and search. */
public final class Normalizer {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SPACES = Pattern.compile("[\\s\\u00A0]+");

    /** Natural order over normalized keys ("Track 2" before "Track 10"); ties broken by the raw strings. */
    public static final Comparator<String> NATURAL = Normalizer::compareNatural;

    private Normalizer() {
    }

    /** NFKD, accents stripped, lower-case, whitespace collapsed and trimmed; {@code null} gives {@code ""}. */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String t = java.text.Normalizer.normalize(s, Form.NFKD);
        t = MARKS.matcher(t).replaceAll("");
        t = t.toLowerCase(Locale.ROOT);
        return SPACES.matcher(t).replaceAll(" ").strip();
    }

    public static int compareNatural(String a, String b) {
        String ka = normalize(a);
        String kb = normalize(b);
        int i = 0;
        int j = 0;
        int zeroDiff = 0;
        while (i < ka.length() && j < kb.length()) {
            char ca = ka.charAt(i);
            char cb = kb.charAt(j);
            if (isDigit(ca) && isDigit(cb)) {
                int ei = i;
                while (ei < ka.length() && isDigit(ka.charAt(ei))) {
                    ei++;
                }
                int ej = j;
                while (ej < kb.length() && isDigit(kb.charAt(ej))) {
                    ej++;
                }
                String ra = ka.substring(i, ei);
                String rb = kb.substring(j, ej);
                String za = stripZeros(ra);
                String zb = stripZeros(rb);
                if (za.length() != zb.length()) {
                    return za.length() < zb.length() ? -1 : 1;
                }
                int c = za.compareTo(zb);
                if (c != 0) {
                    return Integer.signum(c);
                }
                if (zeroDiff == 0) {
                    zeroDiff = ra.length() - rb.length();
                }
                i = ei;
                j = ej;
            } else {
                if (ca != cb) {
                    return ca < cb ? -1 : 1;
                }
                i++;
                j++;
            }
        }
        if (i < ka.length()) {
            return 1;
        }
        if (j < kb.length()) {
            return -1;
        }
        if (zeroDiff != 0) {
            return Integer.signum(zeroDiff);
        }
        if (a == null || b == null) {
            return a == b ? 0 : a == null ? -1 : 1;
        }
        return Integer.signum(a.compareTo(b));
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static String stripZeros(String digits) {
        int k = 0;
        while (k < digits.length() - 1 && digits.charAt(k) == '0') {
            k++;
        }
        return digits.substring(k);
    }
}
