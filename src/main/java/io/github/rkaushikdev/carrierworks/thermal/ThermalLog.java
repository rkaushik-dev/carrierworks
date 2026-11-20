package io.github.rkaushikdev.carrierworks.thermal;

import io.github.rkaushikdev.carrierworks.PerBoard;
import io.github.rkaushikdev.carrierworks.Records;
import io.github.rkaushikdev.carrierworks.util.Numbers;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Recent temperature readings, per board, and the questions asked of them.
 *
 * <p>One reading means very little. The system monitor is noisy at about a degree, so a single
 * sample crossing a threshold is as likely to be the sensor as the part. Everything that decides
 * anything reads an average over a window instead, and the window is short enough that a real
 * excursion is caught inside a few seconds.
 *
 * <p>Staleness is the other half and it is the half that gets forgotten. A board whose last sample
 * is four minutes old is not a cool board, it is a board the bench has stopped hearing from, and
 * treating missing data as good news is how a thermal supervisor quietly stops supervising.
 *
 * <p>The log keeps a bounded number of samples per board. A poll every twenty seconds for a
 * fortnight is sixty thousand readings, none of which anybody looks at after the first hour.
 */
public final class ThermalLog {

    /** How many samples each board keeps unless the log is told otherwise. */
    public static final int DEFAULT_KEEP = 512;

    private static final int TREND_MINIMUM = 4;

    private final PerBoard<ThermalSample> samples;

    /** A log keeping the newest {@value #DEFAULT_KEEP} samples per board. */
    public ThermalLog() {
        this(DEFAULT_KEEP);
    }

    /** A log keeping the newest {@code keep} samples per board; fewer than two is refused. */
    public ThermalLog(int keep) {
        this.samples = new PerBoard<>(keep);
    }

    /** How many samples each board keeps. */
    public int keep() {
        return samples.keep().orElse(DEFAULT_KEEP);
    }

    /** Keep a reading, dropping the board's oldest past the cap, and hand it back. */
    public ThermalSample record(ThermalSample sample) {
        return samples.append(sample.board(), sample);
    }

    /** Keep a reading off the device's own system monitor. */
    public ThermalSample add(String board, Instant at, int millidegrees) {
        return record(new ThermalSample(board, at, millidegrees));
    }

    /** Keep a reading from a named source. */
    public ThermalSample add(String board, Instant at, int millidegrees, String source) {
        return record(new ThermalSample(board, at, millidegrees, source));
    }

    /** Every board with a reading, in tag order. */
    public List<String> boards() {
        return samples.boards();
    }

    /** A board's newest reading, if it has one. */
    public Optional<ThermalSample> latest(String board) {
        return samples.latest(board);
    }

    /** A board's readings taken at or after a moment, oldest first. */
    public List<ThermalSample> since(String board, Instant when) {
        return samples.items(board).stream()
                .filter(sample -> !sample.at().isBefore(when))
                .toList();
    }

    /** A board's readings in a window ending now, ends included, oldest first. */
    public List<ThermalSample> window(String board, Instant now, Duration length) {
        return Records.inWindow(samples.items(board), now, length, ThermalSample::at);
    }

    /** Mean temperature over a window, rounded half to even, or nothing if there is no data. */
    public OptionalInt average(String board, Instant now, Duration length) {
        List<ThermalSample> inside = window(board, now, length);
        if (inside.isEmpty()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of((int) Numbers.round((double) total(inside) / inside.size()));
    }

    /** The hottest reading in a window, or nothing if there is no data. */
    public OptionalInt peak(String board, Instant now, Duration length) {
        return window(board, now, length).stream()
                .mapToInt(ThermalSample::millidegrees)
                .max();
    }

    /**
     * Whether the bench has stopped hearing from a board.
     *
     * <p>A board with no samples at all is stale, not fine. That is the case this exists for.
     */
    public boolean stale(String board, Instant now, Duration limit) {
        return latest(board).map(last -> last.age(now).compareTo(limit) > 0).orElse(true);
    }

    /**
     * Whether the second half of the window is warmer than the first.
     *
     * <p>Nothing when there is not enough to say, which is fewer than four samples, because two
     * noisy readings will happily claim any trend.
     */
    public Optional<Boolean> rising(String board, Instant now, Duration length) {
        List<ThermalSample> inside = window(board, now, length);
        if (inside.size() < TREND_MINIMUM) {
            return Optional.empty();
        }
        int middle = inside.size() / 2;
        double first = (double) total(inside.subList(0, middle)) / middle;
        double second = (double) total(inside.subList(middle, inside.size())) / (inside.size() - middle);
        return Optional.of(second > first);
    }

    /** Drop everything older than a moment, returning how many went. */
    public int prune(Instant before) {
        return samples.prune(sample -> !sample.at().isBefore(before));
    }

    /** How many readings the log holds across every board. */
    public int size() {
        return samples.size();
    }

    private static long total(List<ThermalSample> readings) {
        return readings.stream().mapToLong(ThermalSample::millidegrees).sum();
    }
}
