package org.kendar.dlna.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class NormalizerTest {

    @Test
    void normalizeStripsCaseAccentsAndSpaces() {
        assertThat(Normalizer.normalize("  ÉTÉ   Café ")).isEqualTo("ete cafe");
        assertThat(Normalizer.normalize(null)).isEmpty();
    }

    @Test
    void naturalOrderComparesNumbersByValue() {
        List<String> names = new ArrayList<>(List.of("Track 10", "track 2", "Track 1", "Track 02a"));
        names.sort(Normalizer.NATURAL);
        assertThat(names).containsExactly("Track 1", "track 2", "Track 02a", "Track 10");
    }

    @Test
    void orderIsCaseAndAccentInsensitive() {
        assertThat(Normalizer.compareNatural("éclair", "Fa")).isNegative();
        assertThat(Normalizer.compareNatural("Zebra", "apple")).isPositive();
        assertThat(Normalizer.compareNatural("éclair", "Eclair")).isNotZero();   // raw tie-break only
    }

    @Test
    void longDigitRunsDoNotOverflow() {
        assertThat(Normalizer.compareNatural("a99999999999999999999999", "a100000000000000000000000")).isNegative();
    }

    @Test
    void leadingZerosAreTieBreakOnly() {
        assertThat(Normalizer.compareNatural("a01", "a1")).isPositive();
        assertThat(Normalizer.compareNatural("a1", "a01")).isNegative();
        assertThat(Normalizer.compareNatural("a1", "a1")).isZero();
    }
}
