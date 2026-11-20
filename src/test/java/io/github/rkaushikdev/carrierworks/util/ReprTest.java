package io.github.rkaushikdev.carrierworks.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The quoted forms, checked against what the original bench printed for the same values. */
class ReprTest {

    @Test
    void stringsAreSingleQuotedUnlessTheyHoldOne() {
        assertThat(Repr.of("u2")).isEqualTo("'u2'");
        assertThat(Repr.of("it's")).isEqualTo("\"it's\"");
        assertThat(Repr.of("a\"b")).isEqualTo("'a\"b'");
        assertThat(Repr.of("both ' and \"")).isEqualTo("'both \\' and \"'");
    }

    @Test
    void awkwardCharactersAreEscaped() {
        assertThat(Repr.of("x\ny")).isEqualTo("'x\\ny'");
        assertThat(Repr.of("tab\there")).isEqualTo("'tab\\there'");
        assertThat(Repr.of("back\\slash")).isEqualTo("'back\\\\slash'");
        assertThat(Repr.of("\u0001")).isEqualTo("'\\x01'");
        assertThat(Repr.of("caf\u00e9")).isEqualTo("'caf\u00e9'");
    }

    @Test
    void scalarsPrintAsTheyWouldBeTyped() {
        assertThat(Repr.of(null)).isEqualTo("None");
        assertThat(Repr.of(true)).isEqualTo("True");
        assertThat(Repr.of(false)).isEqualTo("False");
        assertThat(Repr.of(17)).isEqualTo("17");
        assertThat(Repr.of(4211L)).isEqualTo("4211");
        assertThat(Repr.of(0.1)).isEqualTo("0.1");
        assertThat(Repr.of(Optional.empty())).isEqualTo("None");
        assertThat(Repr.of(Optional.of("x"))).isEqualTo("'x'");
    }

    @Test
    void containersQuoteWhatIsInThem() {
        assertThat(Repr.of(List.of("a", 1))).isEqualTo("['a', 1]");
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("k", "v");
        map.put("n", null);
        assertThat(Repr.of(map)).isEqualTo("{'k': 'v', 'n': None}");
    }

    @Test
    void anythingElseIsItsOwnText() {
        assertThat(Repr.of(new StringBuilder("built"))).isEqualTo("'built'");
        assertThat(Repr.of(java.time.Duration.ofSeconds(5))).isEqualTo("PT5S");
    }
}
