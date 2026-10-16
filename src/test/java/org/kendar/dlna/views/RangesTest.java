package org.kendar.dlna.views;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class RangesTest {

    private static List<String> names(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(String.format("Name %03d", i));
        }
        return out;
    }

    @Test
    void shortListsAreNotSplit() {
        assertThat(Ranges.split(names(50), 50, 10)).isEmpty();
        assertThat(Ranges.split(names(3), 50, 10)).isEmpty();
        assertThat(Ranges.split(names(80), 50, 1)).as("ranges below 2 disables the split").isEmpty();
    }

    @Test
    void longListsBecomeBalancedContiguousRanges() {
        List<Ranges.Group> groups = Ranges.split(names(53), 50, 10);
        assertThat(groups).hasSize(10);
        assertThat(groups.get(0).from()).isZero();
        assertThat(groups.get(9).to()).isEqualTo(53);
        for (int i = 1; i < groups.size(); i++) {
            assertThat(groups.get(i).from()).isEqualTo(groups.get(i - 1).to());
        }
        assertThat(groups.stream().mapToInt(Ranges.Group::size).max().orElseThrow()
                - groups.stream().mapToInt(Ranges.Group::size).min().orElseThrow()).isLessThanOrEqualTo(1);
    }

    @Test
    void rangesCutBetweenLettersAndNeverShareOne() {
        List<String> n = new ArrayList<>();
        for (String l : List.of("A", "B", "C", "D", "E", "F")) {
            for (int i = 0; i < 3; i++) {
                n.add(l + "x" + i);
            }
        }
        List<Ranges.Group> groups = Ranges.split(n, 4, 3);
        assertThat(groups).extracting(Ranges.Group::label).containsExactly("A-B", "C-D", "E-F");
    }

    @Test
    void aLetterThatIsTooBigIsSplitOnTheNextCharacter() {
        List<String> n = new ArrayList<>();
        for (String c : List.of("0", "1", "2", "3", "4", "5")) {
            n.add("A" + c);
        }
        n.add("B1");
        List<Ranges.Group> top = Ranges.split(n, 4, 2);
        assertThat(top).extracting(Ranges.Group::label).containsExactly("A", "B");
        List<Ranges.Group> inner = Ranges.split(n.subList(top.get(0).from(), top.get(0).to()), 4, 2, 2);
        assertThat(inner).extracting(Ranges.Group::label).containsExactly("A0-A2", "A3-A5");
    }

    @Test
    void sameInputGivesTheSameRanges() {
        assertThat(Ranges.split(names(200), 50, 10)).isEqualTo(Ranges.split(names(200), 50, 10));
    }

    @Test
    void blankNamesAreLabelledWithAHash() {
        List<String> n = new ArrayList<>(List.of("", "", "", "A", "B", "C"));
        assertThat(Ranges.split(n, 2, 2).get(0).label()).startsWith("#");
    }
}
