package io.github.rkaushikdev.carrierworks.services;

import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.Records;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.jobs.JobState;
import io.github.rkaushikdev.carrierworks.jobs.ProgramJob;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import io.github.rkaushikdev.carrierworks.persistence.UnitOfWork;
import io.github.rkaushikdev.carrierworks.scheduling.Request;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One answer to what the bench is doing, assembled from every other part.
 *
 * <p>This exists because the question gets asked constantly and answering it used to mean four
 * commands and some arithmetic: how many boards are free, who is waiting, is anything too hot, is
 * anything out of calibration. None of that is new information. What is new is that it is one read,
 * one unit of work, and one shape that both the command line and the status page render.
 *
 * <p>The counts are deliberately shallow. Anything that needs a per board answer belongs in that
 * board's own command, and a roll up that tries to be both ends up being neither.
 */
public final class Overview {

    private Overview() {}

    /** The overview on the default settings, counting leases that run out within fifteen minutes. */
    public static BenchOverview overview(UnitOfWork unit, Instant now) {
        return overview(unit, now, BenchSettings.defaults());
    }

    /** The overview on these settings, counting leases that run out within fifteen minutes. */
    public static BenchOverview overview(UnitOfWork unit, Instant now, BenchSettings settings) {
        return overview(unit, now, settings, Leasing.WARNING_WINDOW);
    }

    /**
     * Everything a status page shows, in one pass over the database.
     *
     * @param expiringWithin how close to its end a live lease has to be to count as expiring
     */
    public static BenchOverview overview(
            UnitOfWork unit, Instant now, BenchSettings settings, Duration expiringWithin) {
        List<Board> boards = unit.boards().all();
        Map<String, Integer> states = tally(boards.stream()
                .map(board -> unit.boards().state(board.tag()).state().value())
                .toList());
        List<Lease> held = unit.leases().active(now);
        List<Request> waiting = unit.requests().ordered(now);
        List<ProgramJob> jobs = boards.stream()
                .flatMap(board -> unit.jobs().forBoard(board.tag()).stream())
                .toList();
        Map<String, Integer> thermal = tally(Thermal.statusOf(unit, now, settings).stream()
                .map(status -> status.state().value())
                .toList());
        Instant soon = now.plus(expiringWithin);
        return new BenchOverview(
                now,
                settings.site(),
                states,
                held.size(),
                (int) held.stream()
                        .filter(lease -> !lease.expiresAt().isAfter(soon))
                        .count(),
                waiting.size(),
                waiting.stream()
                        .map(request -> request.waited(now))
                        .max(Comparator.naturalOrder())
                        .map(Times::seconds)
                        .orElse(0L),
                count(jobs, JobState.QUEUED),
                count(jobs, JobState.RUNNING),
                thermal,
                Upkeep.overdue(unit, now).stream().map(CalibrationState::board).toList());
    }

    /** How many times each value appears, in value order. */
    private static Map<String, Integer> tally(List<String> values) {
        return new TreeMap<>(Records.tally(values));
    }

    private static int count(List<ProgramJob> jobs, JobState state) {
        return (int) jobs.stream().filter(job -> job.state() == state).count();
    }
}
