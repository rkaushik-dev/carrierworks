package io.github.rkaushikdev.carrierworks.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The printed and rounded forms, each expected value taken from the original's own output. */
class NumbersTest {

    @ParameterizedTest
    @CsvSource({
        "0.25, 1, 0.2",
        "0.35, 1, 0.3",
        "2.675, 2, 2.67",
        "-0.04, 1, -0.0",
        "1.5, 0, 2",
        "2.5, 0, 2",
        "3.648555755615234, 1, 3.6",
        "62.5, 1, 62.5",
        "30, 3, 30.000",
    })
    void fixedPlacesRoundTheExactValueHalfToEven(double value, int places, String expected) {
        assertThat(Numbers.fixed(value, places)).isEqualTo(expected);
    }

    @Test
    void theFormatterWouldHaveGotSomeOfThemWrong() {
        // The planted control: the cases above are only worth having if the obvious tool differs.
        assertThat(String.format(Locale.ROOT, "%.1f", 0.25)).isNotEqualTo(Numbers.fixed(0.25, 1));
        assertThat(String.format(Locale.ROOT, "%.1f", 0.35)).isNotEqualTo(Numbers.fixed(0.35, 1));
    }

    @Test
    void nonFiniteValuesPrintAsWords() {
        assertThat(Numbers.fixed(Double.NaN, 1)).isEqualTo("nan");
        assertThat(Numbers.fixed(Double.POSITIVE_INFINITY, 1)).isEqualTo("inf");
        assertThat(Numbers.fixed(Double.NEGATIVE_INFINITY, 1)).isEqualTo("-inf");
        assertThatThrownBy(() -> Numbers.fixed(1.0, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"2.5, 2", "3.5, 4", "-2.5, -2", "2.4999, 2", "-0.6, -1"})
    void wholeRoundingGoesHalfToEven(double value, long expected) {
        assertThat(Numbers.round(value)).isEqualTo(expected);
    }

    @Test
    void roundingToPlacesUsesTheExactValue() {
        assertThat(Numbers.round(2.675, 2)).isEqualTo(2.67);
        assertThat(Numbers.round(0.125, 2)).isEqualTo(0.12);
        assertThat(Numbers.round(Double.NaN, 2)).isNaN();
        assertThatThrownBy(() -> Numbers.round(Double.NaN)).isInstanceOf(ArithmeticException.class);
    }

    @ParameterizedTest
    @CsvSource({
        "0.1, 0.1",
        "1.0, 1.0",
        "3.0, 3.0",
        "1e16, 1e+16",
        "1.5e-5, 1.5e-05",
        "123456789.0, 123456789.0",
        "0.0001, 0.0001",
        "-0.0, -0.0",
        "0.0, 0.0",
        "1e100, 1e+100",
        "2.5e-7, 2.5e-07",
        "12345.678, 12345.678",
    })
    void theShortestFormReadsBackExactly(double value, String expected) {
        assertThat(Numbers.shortest(value)).isEqualTo(expected);
        assertThat(Double.parseDouble(expected)).isEqualTo(value);
    }

    @Test
    void theShortestFormOfNonFiniteValues() {
        assertThat(Numbers.shortest(Double.NaN)).isEqualTo("nan");
        assertThat(Numbers.shortest(Double.NEGATIVE_INFINITY)).isEqualTo("-inf");
    }
}
