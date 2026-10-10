package org.kendar.dlna.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class CronExpressionTest {

    private static final ZoneId UTC = ZoneOffset.UTC;

    private static Instant at(String s) {
        return Instant.parse(s);
    }

    @Test
    void defaultEveryTwelveHours() {
        CronExpression c = CronExpression.parse("0 */12 * * *");
        assertThat(c.next(at("2026-03-01T03:15:00Z"), UTC)).isEqualTo(at("2026-03-01T12:00:00Z"));
        assertThat(c.next(at("2026-03-01T12:00:00Z"), UTC)).isEqualTo(at("2026-03-02T00:00:00Z"));
    }

    @Test
    void nextIsStrictlyAfter() {
        CronExpression c = CronExpression.parse("*/15 * * * *");
        assertThat(c.next(at("2026-01-01T10:15:00Z"), UTC)).isEqualTo(at("2026-01-01T10:30:00Z"));
        assertThat(c.next(at("2026-01-01T10:15:59Z"), UTC)).isEqualTo(at("2026-01-01T10:30:00Z"));
    }

    @Test
    void listsRangesAndSteps() {
        CronExpression c = CronExpression.parse("5,35 8-10/2 * * *");
        assertThat(c.next(at("2026-01-01T00:00:00Z"), UTC)).isEqualTo(at("2026-01-01T08:05:00Z"));
        assertThat(c.next(at("2026-01-01T08:35:00Z"), UTC)).isEqualTo(at("2026-01-01T10:05:00Z"));
        assertThat(c.next(at("2026-01-01T10:35:00Z"), UTC)).isEqualTo(at("2026-01-02T08:05:00Z"));
    }

    @Test
    void weekdayAndMonthNames() {
        CronExpression c = CronExpression.parse("30 6 * jan mon");
        // 2026-01-05 is a Monday
        assertThat(c.next(at("2026-01-01T00:00:00Z"), UTC)).isEqualTo(at("2026-01-05T06:30:00Z"));
        assertThat(c.next(at("2026-02-01T00:00:00Z"), UTC)).isEqualTo(at("2027-01-04T06:30:00Z"));
    }

    @Test
    void sundayIsZeroOrSeven() {
        assertThat(CronExpression.parse("0 0 * * 0").next(at("2026-01-01T00:00:00Z"), UTC))
                .isEqualTo(at("2026-01-04T00:00:00Z"));
        assertThat(CronExpression.parse("0 0 * * 7").next(at("2026-01-01T00:00:00Z"), UTC))
                .isEqualTo(at("2026-01-04T00:00:00Z"));
    }

    @Test
    void dayOfMonthOrDayOfWeekWhenBothRestricted() {
        CronExpression c = CronExpression.parse("0 0 15 * mon");
        assertThat(c.next(at("2026-01-01T00:00:00Z"), UTC)).isEqualTo(at("2026-01-05T00:00:00Z"));
        assertThat(c.next(at("2026-01-12T00:00:00Z"), UTC)).isEqualTo(at("2026-01-15T00:00:00Z"));
    }

    @Test
    void honoursTheTimeZone() {
        CronExpression c = CronExpression.parse("0 3 * * *");
        assertThat(c.next(at("2026-01-01T00:00:00Z"), ZoneId.of("Europe/Rome")))
                .isEqualTo(at("2026-01-01T02:00:00Z"));
    }

    @Test
    void untilNext() {
        assertThat(CronExpression.parse("0 * * * *").untilNext(at("2026-01-01T10:20:00Z"), UTC))
                .isEqualTo(Duration.ofMinutes(40));
    }

    @Test
    void leapDayFindsTheNextLeapYear() {
        assertThat(CronExpression.parse("0 0 29 2 *").next(at("2026-03-01T00:00:00Z"), UTC))
                .isEqualTo(at("2028-02-29T00:00:00Z"));
    }

    @Test
    void invalidExpressionsAreRejectedWithAMessage() {
        for (String bad : new String[] { "", "  ", "* * * *", "* * * * * *", "60 * * * *", "* 24 * * *", "* * 0 * *",
                "* * * 13 *", "* * * * 8", "a * * * *", "*/0 * * * *", "5-1 * * * *", "1,,2 * * * *" }) {
            assertThatThrownBy(() -> CronExpression.parse(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
