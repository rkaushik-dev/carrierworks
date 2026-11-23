package io.github.rkaushikdev.carrierworks.thermal;

import io.github.rkaushikdev.carrierworks.PerBoard;
import io.github.rkaushikdev.carrierworks.Records;
import io.github.rkaushikdev.carrierworks.util.Numbers;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.stream.Collectors;

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

    /**
     * The temperature the board is judged on over a window: each sensor's mean, rounded half to
     * even, and the hottest of those. Nothing if there is no data.
     *
     * <p>Sensors are not averaged together. A rack controller's inlet sensor reads thirty degrees
     * while the die it sits beside reads ninety, and the mean of the two is a board that looks
     * comfortable right up to the moment it shuts itself down.
     */
    public OptionalInt average(String board, Instant now, Duration length) {
        return averages(board, now, length).values().stream()
                .mapToInt(Integer::intValue)
                .max();
    }

    /** Each sensor's mean over a window, rounded half to even, by source in name order. */
    public Map<String, Integer> averages(String board, Instant now, Duration length) {
        Map<String, Integer> means = new TreeMap<>();
        bySource(window(board, now, length))
                .forEach((source, readings) ->
                        means.put(source, (int) Numbers.round((double) total(readings) / readings.size())));
        return Collections.unmodifiableMap(means);
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
     * Whether the second half of the window is warmer than the first, on the sensor the board is
     * judged on.
     *
     * <p>Nothing when there is not enough to say, which is fewer than four samples from that
     * sensor, because two noisy readings will happily claim any trend. Readings from two sensors
     * are never mixed into one trend: an ambient sample landing late in the window would make a
     * heating die look as if it were cooling.
     */
    public Optional<Boolean> rising(String board, Instant now, Duration length) {
        return risePerMinute(board, now, length).stream()
                .mapToObj(rate -> rate > 0)
                .findFirst();
    }

    /**
     * How fast the board is heating, in millidegrees a minute, as the least squares slope through
     * the readings of the sensor the board is judged on; negative when it is cooling.
     *
     * <p>Nothing when that sensor has fewer than four readings in the window, or they were all
     * taken at the same moment.
     */
    public OptionalInt risePerMinute(String board, Instant now, Duration length) {
        List<ThermalSample> inside = hottestSensor(board, now, length);
        if (inside.size() < TREND_MINIMUM) {
            return OptionalInt.empty();
        }
        Instant first = inside.getFirst().at();
        double meanSeconds = inside.stream()
                .mapToDouble(sample -> seconds(first, sample))
                .average()
                .orElseThrow();
        double meanReading = (double) total(inside) / inside.size();
        double across = 0;
        double spread = 0;
        for (ThermalSample sample : inside) {
            double dx = seconds(first, sample) - meanSeconds;
            across += dx * (sample.millidegrees() - meanReading);
            spread += dx * dx;
        }
        if (spread == 0) {
            return OptionalInt.empty();
        }
        return OptionalInt.of((int) Numbers.round(across / spread * 60));
    }

    /** Drop everything older than a moment, returning how many went. */
    public int prune(Instant before) {
        return samples.prune(sample -> !sample.at().isBefore(before));
    }

    /** How many readings the log holds across every board. */
    public int size() {
        return samples.size();
    }

    /** The window's readings from whichever sensor reads hottest on average, oldest first. */
    List<ThermalSample> hottestSensor(String board, Instant now, Duration length) {
        Map<String, List<ThermalSample>> sources = bySource(window(board, now, length));
        Map<String, Integer> means = averages(board, now, length);
        return means.entrySet().stream()
                .max(Map.Entry.<String, Integer>comparingByValue())
                .map(hottest -> sources.get(hottest.getKey()))
                .orElse(List.of());
    }

    private static Map<String, List<ThermalSample>> bySource(List<ThermalSample> readings) {
        return readings.stream()
                .collect(Collectors.groupingBy(ThermalSample::source, TreeMap::new, Collectors.toList()));
    }

    private static double seconds(Instant from, ThermalSample sample) {
        return Duration.between(from, sample.at()).toMillis() / 1000.0;
    }

    private static long total(List<ThermalSample> readings) {
        return readings.stream().mapToLong(ThermalSample::millidegrees).sum();
    }
}
