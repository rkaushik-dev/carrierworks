package io.github.rkaushikdev.carrierworks.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonValuesTest {

    @Test
    void textIsAStringAsItselfAndAnythingElseQuoted() {
        assertThat(JsonValues.text("bx-14")).isEqualTo("bx-14");
        assertThat(JsonValues.text(null)).isEqualTo("None");
        assertThat(JsonValues.text(true)).isEqualTo("True");
        assertThat(JsonValues.text(1800L)).isEqualTo("1800");
        assertThat(JsonValues.text(1.5)).isEqualTo("1.5");
        assertThat(JsonValues.text(List.of("a", 1L))).isEqualTo("['a', 1]");
    }

    @Test
    void emptyAndZeroValuesAreNotGivenAndEverythingElseIs() {
        assertThat(Arrays.asList(null, false, 0L, 0.0, "", List.of(), Map.of()))
                .allSatisfy(value -> assertThat(JsonValues.truthy(value)).isFalse());
        assertThat(List.of(true, 7L, -0.5, Double.NaN, " ", List.of(0L), Map.of("a", 0L), new Object()))
                .allSatisfy(value -> assertThat(JsonValues.truthy(value)).isTrue());
    }

    @Test
    void wholeNumbersAreReadFromNumbersFlagsAndText() {
        assertThat(JsonValues.whole(1800L)).isEqualTo(1800);
        assertThat(JsonValues.whole(1200.9)).isEqualTo(1200);
        assertThat(JsonValues.whole(-2.5)).isEqualTo(-2);
        assertThat(JsonValues.whole(true)).isEqualTo(1);
        assertThat(JsonValues.whole(" -1_800 ")).isEqualTo(-1800);
        assertThat(JsonValues.whole("+42")).isEqualTo(42);
    }

    @Test
    void whatIsNotAWholeNumberIsRefused() {
        assertThatThrownBy(() -> JsonValues.whole("1800.0")).isInstanceOf(NumberFormatException.class);
        assertThatThrownBy(() -> JsonValues.whole("1__800")).isInstanceOf(NumberFormatException.class);
        assertThatThrownBy(() -> JsonValues.whole(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("not a whole number: None");
        assertThatThrownBy(() -> JsonValues.whole(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JsonValues.whole(List.of(1L))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMissingMemberFallsBackButAMemberHoldingNullDoesNot() {
        Map<String, Object> object = new HashMap<>();
        object.put("notes", null);
        assertThat(JsonValues.get(object, "revision", "a")).isEqualTo("a");
        assertThat(JsonValues.get(object, "notes", "")).isNull();
    }
}
