package io.github.rkaushikdev.carrierworks.util;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * Timestamps in the text form the bench writes them in, and the clock it reads them from.
 *
 * <p>Every moment the bench keeps is UTC, held as an {@link Instant} to the microsecond, which is
 * as fine as the database stores one. Written out it carries an explicit {@code +00:00} rather
 * than a {@code Z}, the seconds always, and a fraction only when there is one, so the journal and
 * the audit trail keep one stable form.
 */
public final class Times {

    /** How much of the time of day an ISO timestamp spells out. */
    public enum Precision {
        /** Seconds always, and a six digit fraction only when the moment has one. */
        AUTO,
        /** Hours and minutes. */
        MINUTES,
        /** Hours, minutes and seconds. */
        SECONDS,
        /** Seconds and a three digit fraction. */
        MILLISECONDS,
        /** Seconds and a six digit fraction. */
        MICROSECONDS
    }

    private static final DateTimeFormatter LENIENT = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .optionalStart()
            .appendLiteral('T')
            .optionalEnd()
            .optionalStart()
            .appendLiteral(' ')
            .optionalEnd()
            .appendValue(ChronoField.HOUR_OF_DAY, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .optionalStart()
            .appendLiteral(':')
            .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
            .optionalEnd()
            .optionalEnd()
            .appendOffset("+HH:MM:ss", "Z")
            .toFormatter(Locale.ROOT);

    private Times() {}

    /** The current moment, to the microsecond. */
    public static Instant now(Clock clock) {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** The current moment by the system clock, to the microsecond. */
    public static Instant now() {
        return now(Clock.systemUTC());
    }

    /** {@code 2026-01-15T02:00:00+00:00}, with a fraction only when there is one. */
    public static String iso(Instant moment) {
        return iso(moment, Precision.AUTO);
    }

    /** The moment in UTC, spelled out to the precision asked for. */
    public static String iso(Instant moment, Precision precision) {
        return iso(moment.atOffset(ZoneOffset.UTC), precision);
    }

    /** A moment at some offset, spelled out to the precision asked for. */
    public static String iso(OffsetDateTime moment, Precision precision) {
        return stamp(moment, 'T', precision);
    }

    /** {@code 2026-01-15 02:00:00+00:00}: the form a timestamp takes in printed listings. */
    public static String text(Instant moment) {
        return stamp(moment.atOffset(ZoneOffset.UTC), ' ', Precision.AUTO);
    }

    /**
     * Read a timestamp back from any of the ISO forms the bench writes or accepts.
     *
     * <p>{@code T} or a space between date and time, seconds and fraction optional, and an offset
     * that is required, as {@code Z} or {@code +HH:MM}.
     *
     * @throws DateTimeParseException when the text is not one, or names no offset
     */
    public static Instant parse(String text) {
        return OffsetDateTime.parse(text.strip(), LENIENT).toInstant();
    }

    /** Whole seconds between two moments, truncated toward zero. */
    public static long seconds(Duration duration) {
        long seconds = duration.getSeconds();
        if (seconds < 0 && duration.getNano() > 0) {
            seconds += 1;
        }
        return seconds;
    }

    /** The length of a duration in seconds, fraction and all. */
    public static double totalSeconds(Duration duration) {
        return duration.getSeconds() + duration.getNano() / 1_000_000_000.0;
    }

    private static String stamp(OffsetDateTime moment, char separator, Precision precision) {
        StringBuilder out = new StringBuilder(32);
        out.append(moment.toLocalDate()).append(separator);
        out.append(two(moment.getHour())).append(':').append(two(moment.getMinute()));
        int micros = moment.getNano() / 1000;
        switch (precision) {
            case MINUTES -> {}
            case SECONDS -> out.append(':').append(two(moment.getSecond()));
            case MILLISECONDS ->
                out.append(':')
                        .append(two(moment.getSecond()))
                        .append('.')
                        .append(String.format(Locale.ROOT, "%03d", micros / 1000));
            case MICROSECONDS ->
                out.append(':')
                        .append(two(moment.getSecond()))
                        .append('.')
                        .append(String.format(Locale.ROOT, "%06d", micros));
            case AUTO -> {
                out.append(':').append(two(moment.getSecond()));
                if (micros != 0) {
                    out.append('.').append(String.format(Locale.ROOT, "%06d", micros));
                }
            }
        }
        return out.append(offset(moment.getOffset())).toString();
    }

    private static String offset(ZoneOffset offset) {
        int total = offset.getTotalSeconds();
        char sign = total < 0 ? '-' : '+';
        int magnitude = Math.abs(total);
        String text = sign + two(magnitude / 3600) + ":" + two((magnitude / 60) % 60);
        return magnitude % 60 == 0 ? text : text + ":" + two(magnitude % 60);
    }

    private static String two(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }
}
