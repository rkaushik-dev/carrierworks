package io.github.rkaushikdev.carrierworks.services;

import io.github.rkaushikdev.carrierworks.AuditEntry;
import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.JunctionRange;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.ThermalLimits;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.persistence.UnitOfWork;
import io.github.rkaushikdev.carrierworks.thermal.AlarmBook;
import io.github.rkaushikdev.carrierworks.thermal.SupervisorAction;
import io.github.rkaushikdev.carrierworks.thermal.ThermalLog;
import io.github.rkaushikdev.carrierworks.thermal.ThermalSample;
import io.github.rkaushikdev.carrierworks.thermal.ThermalStatus;
import io.github.rkaushikdev.carrierworks.thermal.ThermalSupervisor;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Temperature readings, and the supervisor pass that runs off them.
 *
 * <p>The supervisor reads a log in memory, and the reason it stays that way is that a tick has to
 * look at a window of readings for every board at once. Loading that window in one query and
 * handing it over as a log costs one round trip; asking the database per board inside the decision
 * loop costs one per board per tick, on the busiest path the bench has.
 *
 * <p>So this does three things: it writes readings, it loads a window, and it runs a tick over that
 * window. Everything a tick decides comes back as actions rather than as changes it has already
 * made, except the shutdown path, which has to act while it still has the unit of work.
 */
public final class Thermal {

    /** How far back a status pass or a tick reads, unless it is told otherwise. */
    public static final Duration DEFAULT_WINDOW = Duration.ofMinutes(15);

    /** How long readings are kept, unless a prune is told otherwise. */
    public static final Duration DEFAULT_RETENTION = Duration.ofDays(7);

    /** The junction range the limits fall back to when the bench has no boards to judge by. */
    private static final JunctionRange COMMERCIAL = new JunctionRange(0, 85);

    private static final String ACTOR = "thermal";
    private static final Set<SupervisorAction.Kind> WORTH_A_LINE =
            Set.of(SupervisorAction.Kind.SHUTDOWN, SupervisorAction.Kind.WARN);

    private Thermal() {}

    /**
     * One reading as a rack controller posts it, not yet checked.
     *
     * @param board the asset tag, however it was written
     * @param at when it was taken
     * @param millidegrees the temperature, in thousandths of a degree Celsius
     * @param source which sensor it came off
     */
    public record Reading(String board, Instant at, int millidegrees, String source) {

        /** A reading off the device's own system monitor. */
        public Reading(String board, Instant at, int millidegrees) {
            this(board, at, millidegrees, ThermalSample.DEFAULT_SOURCE);
        }
    }

    /** A reading off the device's own system monitor. */
    public static ThermalSample record(UnitOfWork unit, String board, Instant at, int millidegrees) {
        return record(unit, board, at, millidegrees, ThermalSample.DEFAULT_SOURCE);
    }

    /**
     * Store one reading, refusing one for a board the bench does not have.
     *
     * @throws NotFound when the board is not on the bench
     * @throws io.github.rkaushikdev.carrierworks.ValidationError when it is not a temperature a
     *     device reports
     */
    public static ThermalSample record(UnitOfWork unit, String board, Instant at, int millidegrees, String source) {
        unit.boards().get(board);
        return unit.thermal().record(new ThermalSample(board, at, millidegrees, source));
    }

    /**
     * Store a whole controller's poll at once, or none of it.
     *
     * <p>Every reading is checked before anything is written, so a batch naming a board the bench
     * does not have is refused rather than half stored. That matters because the caller retries the
     * batch, and a half stored batch turns a retry into duplicate readings.
     *
     * @throws NotFound naming the first board the bench does not have
     * @throws io.github.rkaushikdev.carrierworks.ValidationError when a reading is not a temperature
     */
    public static List<ThermalSample> recordBatch(UnitOfWork unit, List<Reading> readings) {
        Set<String> known = unit.boards().all().stream().map(Board::tag).collect(Collectors.toSet());
        List<ThermalSample> samples = new ArrayList<>();
        for (Reading reading : readings) {
            ThermalSample parsed =
                    new ThermalSample(reading.board(), reading.at(), reading.millidegrees(), reading.source());
            if (!known.contains(parsed.board())) {
                throw new NotFound("board", reading.board());
            }
            samples.add(parsed);
        }
        unit.thermal().recordMany(samples);
        return List.copyOf(samples);
    }

    /** Every reading in the last stretch, as a log. */
    public static ThermalLog loadWindow(UnitOfWork unit, Instant now, Duration length) {
        return unit.thermal().load(now.minus(length));
    }

    /**
     * The tightest limits any part on the bench is rated for.
     *
     * <p>One book watches the whole bench, so it gets one set of thresholds, and the only safe way
     * to pick them is the coolest part fitted. A rack of industrial parts with one commercial board
     * in it is judged by the commercial board, which is the board that will fail first. An empty
     * bench is judged as commercial.
     */
    public static ThermalLimits limitsFor(UnitOfWork unit) {
        int top = unit.boards().all().stream()
                .mapToInt(board -> board.part().junctionRange().high())
                .min()
                .orElse(COMMERCIAL.high());
        return ThermalLimits.fromJunctionRange(new JunctionRange(0, top));
    }

    /** {@link #statusOf(UnitOfWork, Instant, BenchSettings, Duration)} on the default settings and window. */
    public static List<ThermalStatus> statusOf(UnitOfWork unit, Instant now) {
        return statusOf(unit, now, BenchSettings.defaults(), DEFAULT_WINDOW);
    }

    /** {@link #statusOf(UnitOfWork, Instant, BenchSettings, Duration)} over the default window. */
    public static List<ThermalStatus> statusOf(UnitOfWork unit, Instant now, BenchSettings settings) {
        return statusOf(unit, now, settings, DEFAULT_WINDOW);
    }

    /**
     * Where every board stands, worst first, without acting on any of it.
     *
     * <p>Every board on the bench gets a line, including the ones that have not reported at all. A
     * board missing from the answer looks like a board that is fine, and the silent ones are the
     * ones worth looking at.
     */
    public static List<ThermalStatus> statusOf(UnitOfWork unit, Instant now, BenchSettings settings, Duration window) {
        ThermalLog log = loadWindow(unit, now, window);
        AlarmBook alarms = freshBook(unit, settings);
        for (Board board : unit.boards().all()) {
            alarms.update(board.tag(), log, now);
        }
        return alarms.worstFirst();
    }

    /** A tick on the default settings, over the default window, with a book built for it. */
    public static List<SupervisorAction> tick(UnitOfWork unit, Instant now) {
        return tick(unit, now, BenchSettings.defaults(), DEFAULT_WINDOW);
    }

    /** A tick on the default settings, over the default window, carrying on from this book. */
    public static List<SupervisorAction> tick(UnitOfWork unit, Instant now, AlarmBook alarms) {
        return tick(unit, now, DEFAULT_WINDOW, alarms);
    }

    /** A tick with an alarm book built for it from the bench's own limits and the settings. */
    public static List<SupervisorAction> tick(UnitOfWork unit, Instant now, BenchSettings settings, Duration window) {
        return tick(unit, now, window, freshBook(unit, settings));
    }

    /**
     * One supervisor pass over the stored readings, carrying on from an alarm book.
     *
     * <p>A long running poller should pass the same book every tick: the states have hysteresis in
     * them, and a book built fresh each time has no history to be hysteretic about. A shutdown or a
     * warning leaves a line in the trail; a note does not.
     */
    public static List<SupervisorAction> tick(UnitOfWork unit, Instant now, Duration window, AlarmBook alarms) {
        ThermalSupervisor supervisor =
                new ThermalSupervisor(new BookOverRepository(unit), alarms, new LeaseStoreOverRepository(unit));
        List<SupervisorAction> actions = supervisor.tick(loadWindow(unit, now, window), now);
        for (SupervisorAction action : actions) {
            if (WORTH_A_LINE.contains(action.action())) {
                unit.audit()
                        .add(new AuditEntry(
                                now,
                                ACTOR,
                                action.action().value(),
                                action.board(),
                                Audit.trimmed(action.reason()),
                                ""));
            }
        }
        return actions;
    }

    /** {@link #prune(UnitOfWork, Instant, Duration)} keeping {@link #DEFAULT_RETENTION}. */
    public static int prune(UnitOfWork unit, Instant now) {
        return prune(unit, now, DEFAULT_RETENTION);
    }

    /** Drop readings older than the retention window, and say how many went. */
    public static int prune(UnitOfWork unit, Instant now, Duration keep) {
        return unit.thermal().prune(now.minus(keep));
    }

    private static AlarmBook freshBook(UnitOfWork unit, BenchSettings settings) {
        return new AlarmBook(
                limitsFor(unit), AlarmBook.DEFAULT_WINDOW, Duration.ofSeconds(settings.thermalStaleSeconds()));
    }
}
