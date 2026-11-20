package io.github.rkaushikdev.carrierworks.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Timestamps in the bench's text form, and back. */
class TimesTest {

    private static final Instant MOMENT = Instant.parse("2026-01-15T02:00:00Z");
    private static final Instant RAGGED = MOMENT.plusNanos(123_456_000);

    @Test
    void theDefaultFormShowsSecondsAndAFractionOnlyWhenThereIsOne() {
        assertThat(Times.iso(MOMENT)).isEqualTo("2026-01-15T02:00:00+00:00");
        assertThat(Times.iso(RAGGED)).isEqualTo("2026-01-15T02:00:00.123456+00:00");
    }

    @Test
    void eachPrecisionSpellsOutWhatItSays() {
        assertThat(Times.iso(RAGGED, Times.Precision.MINUTES)).isEqualTo("2026-01-15T02:00+00:00");
        assertThat(Times.iso(RAGGED, Times.Precision.SECONDS)).isEqualTo("2026-01-15T02:00:00+00:00");
        assertThat(Times.iso(RAGGED, Times.Precision.MILLISECONDS)).isEqualTo("2026-01-15T02:00:00.123+00:00");
        assertThat(Times.iso(MOMENT, Times.Precision.MICROSECONDS)).isEqualTo("2026-01-15T02:00:00.000000+00:00");
    }

    @Test
    void anOffsetIsWrittenAsHoursAndMinutes() {
        assertThat(Times.iso(MOMENT.atOffset(ZoneOffset.ofHours(-5)), Times.Precision.SECONDS))
                .isEqualTo("2026-01-14T21:00:00-05:00");
    }

    @Test
    void theListingFormHasASpace() {
        assertThat(Times.text(MOMENT)).isEqualTo("2026-01-15 02:00:00+00:00");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "2026-01-15T02:00:00+00:00",
                "2026-01-15 02:00:00+00:00",
                "2026-01-15T02:00Z",
                "2026-01-14T21:00:00-05:00",
                "2026-01-15T02:00:00.000+00:00"
            })
    void everyFormTheBenchWritesReadsBack(String text) {
        assertThat(Times.parse(text)).isEqualTo(MOMENT);
    }

    @Test
    void aFractionSurvivesTheRoundTrip() {
        assertThat(Times.parse(Times.iso(RAGGED))).isEqualTo(RAGGED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-01-15T02:00:00", "2026-01-15", "yesterday", ""})
    void aTimestampWithoutAnOffsetIsRefused(String text) {
        assertThatThrownBy(() -> Times.parse(text)).isInstanceOf(DateTimeParseException.class);
    }

    @Test
    void theClockIsReadToTheMicrosecond() {
        Clock fixed = Clock.fixed(MOMENT.plusNanos(123_456_789), ZoneOffset.UTC);
        assertThat(Times.now(fixed)).isEqualTo(MOMENT.plusNanos(123_456_000));
        assertThat(Times.now().getNano() % 1000).isZero();
    }

    @Test
    void wholeSecondsTruncateTowardZero() {
        assertThat(Times.seconds(Duration.ofMillis(1500))).isEqualTo(1);
        assertThat(Times.seconds(Duration.ofMillis(-1500))).isEqualTo(-1);
        assertThat(Times.seconds(Duration.ofSeconds(-2))).isEqualTo(-2);
        assertThat(Times.totalSeconds(Duration.ofMillis(-1500))).isEqualTo(-1.5);
    }
}
