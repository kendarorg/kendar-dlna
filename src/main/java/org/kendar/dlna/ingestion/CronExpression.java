package org.kendar.dlna.ingestion;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;

/**
 * Minimal 5-field cron expression: {@code minute hour day-of-month month day-of-week}. Each field accepts
 * {@code *}, numbers, ranges {@code a-b}, steps {@code *}{@code /n} and {@code a-b/n}, and comma lists. Month and
 * weekday also accept three-letter English names; weekday 0 and 7 are Sunday. When both day-of-month and
 * day-of-week are restricted a day matches if either does (classic cron).
 */
public final class CronExpression {

    private static final List<String> MONTHS = List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep",
            "oct", "nov", "dec");
    private static final List<String> DAYS = List.of("sun", "mon", "tue", "wed", "thu", "fri", "sat");

    private final String text;
    private final BitSet minutes;
    private final BitSet hours;
    private final BitSet daysOfMonth;
    private final BitSet months;
    private final BitSet daysOfWeek;
    private final boolean domRestricted;
    private final boolean dowRestricted;

    private CronExpression(String text, BitSet minutes, BitSet hours, BitSet daysOfMonth, BitSet months,
            BitSet daysOfWeek, boolean domRestricted, boolean dowRestricted) {
        this.text = text;
        this.minutes = minutes;
        this.hours = hours;
        this.daysOfMonth = daysOfMonth;
        this.months = months;
        this.daysOfWeek = daysOfWeek;
        this.domRestricted = domRestricted;
        this.dowRestricted = dowRestricted;
    }

    /** @throws IllegalArgumentException with a clear message when the expression is invalid */
    public static CronExpression parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("cron expression is empty");
        }
        String[] f = expression.strip().split("\\s+");
        if (f.length != 5) {
            throw new IllegalArgumentException("cron expression must have 5 fields (minute hour day-of-month month "
                    + "day-of-week), got " + f.length + ": '" + expression + "'");
        }
        BitSet dow = field(f[4], 0, 7, DAYS, 0, "day-of-week");
        if (dow.get(7)) {
            dow.set(0);
            dow.clear(7);
        }
        return new CronExpression(expression.strip(), field(f[0], 0, 59, null, 0, "minute"),
                field(f[1], 0, 23, null, 0, "hour"), field(f[2], 1, 31, null, 0, "day-of-month"),
                field(f[3], 1, 12, MONTHS, 1, "month"), dow, !f[2].startsWith("*"), !f[4].startsWith("*"));
    }

    private static BitSet field(String spec, int min, int max, List<String> names, int nameBase, String what) {
        BitSet set = new BitSet(max + 1);
        for (String part : spec.split(",", -1)) {
            String range = part;
            int step = 1;
            int slash = part.indexOf('/');
            if (slash >= 0) {
                range = part.substring(0, slash);
                step = number(part.substring(slash + 1), null, 0, what + " step");
                if (step < 1) {
                    throw new IllegalArgumentException(what + " step must be positive: '" + part + "'");
                }
            }
            int from;
            int to;
            if (range.equals("*")) {
                from = min;
                to = max == 7 ? 6 : max;   // '*' in day-of-week means 0-6
            } else {
                int dash = range.indexOf('-');
                if (dash > 0) {
                    from = number(range.substring(0, dash), names, nameBase, what);
                    to = number(range.substring(dash + 1), names, nameBase, what);
                } else {
                    from = number(range, names, nameBase, what);
                    to = slash >= 0 ? max : from;
                }
            }
            if (from < min || to > max || from > to) {
                throw new IllegalArgumentException(what + " out of range " + min + "-" + max + ": '" + part + "'");
            }
            for (int v = from; v <= to; v += step) {
                set.set(v);
            }
        }
        return set;
    }

    private static int number(String s, List<String> names, int nameBase, String what) {
        if (names != null) {
            int i = names.indexOf(s.toLowerCase(Locale.ROOT));
            if (i >= 0) {
                return i + nameBase;
            }
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid " + what + " value: '" + s + "'");
        }
    }

    /** The first fire time strictly after {@code after}, in the given zone. */
    public Instant next(Instant after, ZoneId zone) {
        ZonedDateTime t = ZonedDateTime.ofInstant(after, zone).truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        ZonedDateTime limit = t.plusYears(8);   // covers Feb 29 on a weekday
        while (t.isBefore(limit)) {
            if (!months.get(t.getMonthValue())) {
                t = t.plusMonths(1).withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
            } else if (!dayMatches(t)) {
                t = t.plusDays(1).truncatedTo(ChronoUnit.DAYS);
            } else if (!hours.get(t.getHour())) {
                t = t.plusHours(1).truncatedTo(ChronoUnit.HOURS);
            } else if (!minutes.get(t.getMinute())) {
                t = t.plusMinutes(1);
            } else {
                return t.toInstant();
            }
        }
        throw new IllegalStateException("cron expression never fires: " + text);
    }

    /** Time from {@code now} to the next fire. */
    public Duration untilNext(Instant now, ZoneId zone) {
        return Duration.between(now, next(now, zone));
    }

    private boolean dayMatches(ZonedDateTime t) {
        boolean dom = daysOfMonth.get(t.getDayOfMonth());
        boolean dow = daysOfWeek.get(t.getDayOfWeek().getValue() % 7);
        if (domRestricted && dowRestricted) {
            return dom || dow;
        }
        return dom && dow;
    }

    @Override
    public String toString() {
        return text;
    }
}
