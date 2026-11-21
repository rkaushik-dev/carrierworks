package io.github.rkaushikdev.carrierworks.cli;

import io.github.rkaushikdev.carrierworks.inventory.FaultLog;
import io.github.rkaushikdev.carrierworks.jobs.JobJournal;
import io.github.rkaushikdev.carrierworks.jobs.ProgramJob;
import io.github.rkaushikdev.carrierworks.jobs.RecoveryReport;
import io.github.rkaushikdev.carrierworks.jobs.RunResult;
import io.github.rkaushikdev.carrierworks.services.DispatchOptions;
import io.github.rkaushikdev.carrierworks.services.DispatchOutcome;
import io.github.rkaushikdev.carrierworks.services.Execution;
import io.github.rkaushikdev.carrierworks.services.Recovery;
import io.github.rkaushikdev.carrierworks.services.Scheduling;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code fpga worker}: the loop that actually gets work done on a bench nobody is watching.
 *
 * <p>It does three things in order and the order is the point. Recovery first, because a job left
 * running by the last crash is holding a board that nothing else will release. Then the waiting
 * list, because a request that has been waiting an hour should get a board before a job that was
 * queued a minute ago gets run on one. Then the jobs.
 *
 * <p>There is no daemon here. The loop runs a fixed number of rounds and exits, and whatever runs it
 * on a timer decides how often that is. A worker that supervises itself is a worker that has to be
 * supervised.
 */
@Command(
        name = "worker",
        description = "Running the bench without anybody at it.",
        subcommands = {WorkerCommands.Recover.class, WorkerCommands.Run.class})
final class WorkerCommands {

    private WorkerCommands() {}

    /** One row per thing recovery did, jobs first and then boards. */
    private static List<Map<String, Object>> rowsOf(RecoveryReport report) {
        List<Map<String, Object>> rows = new ArrayList<>();
        addRows(rows, "requeued", report.jobsRequeued());
        addRows(rows, "closed", report.jobsClosed());
        addRows(rows, "faulted", report.boardsFaulted());
        addRows(rows, "returned", report.boardsReturned());
        addRows(rows, "unknown", report.unknownJobs());
        return rows;
    }

    private static void addRows(List<Map<String, Object>> rows, String what, List<String> subjects) {
        subjects.forEach(subject -> rows.add(BenchCommand.fields("what", what, "subject", subject)));
    }

    /** Close out whatever was in flight when this bench last stopped. */
    @Command(name = "recover", description = "Close out whatever was in flight when this bench last stopped.")
    static final class Recover extends BenchCommand {

        @Override
        int run(Context context) {
            JobJournal journal = Execution.journalFor(context.settings());
            RecoveryReport report =
                    context.apply(unit -> Recovery.recover(unit, journal, context.now(), context.settings()));
            if (report.clean()) {
                context.say("nothing was interrupted", fields("recovered", List.of()));
                return OK;
            }
            context.emit(rowsOf(report), "what", "subject");
            return OK;
        }
    }

    /** Recover, hand out boards, then run whatever jobs are ready. */
    @Command(name = "run", description = "Recover, hand out boards, then run whatever jobs are ready.")
    static final class Run extends BenchCommand {

        @Option(names = "--rounds", paramLabel = "INTEGER", description = "how many times round the loop")
        private int rounds = 1;

        @Option(names = "--live", description = "use the real cables rather than the simulator")
        private boolean live;

        @Option(names = "--no-dispatch", description = "run jobs but do not hand out boards")
        private boolean noDispatch;

        @Option(names = "--no-recover", description = "skip the recovery pass at the start")
        private boolean noRecover;

        @Override
        int run(Context context) {
            if (rounds < 1) {
                throw new UsageError("a worker runs at least one round");
            }
            JobJournal journal = Execution.journalFor(context.settings());
            FaultLog faults = new FaultLog();
            List<String> recovered = new ArrayList<>();
            List<String> assigned = new ArrayList<>();
            List<Map<String, Object>> ran = new ArrayList<>();
            context.run(unit -> {
                if (!noRecover) {
                    RecoveryReport report = Recovery.recover(unit, journal, context.now(), context.settings());
                    if (!report.clean()) {
                        recovered.add(report.describe());
                    }
                }
                for (int round = 0; round < rounds; round++) {
                    if (!noDispatch) {
                        DispatchOutcome outcome = Scheduling.dispatch(
                                unit, context.now(), DispatchOptions.standard().withSettings(context.settings()));
                        outcome.assigned().forEach(served -> assigned.add(served.request() + " -> " + served.board()));
                    }
                    Optional<ProgramJob> job = Execution.nextReady(unit, context.now());
                    if (job.isEmpty()) {
                        break;
                    }
                    RunResult result =
                            Execution.runJob(unit, job.get(), context.now(), context.settings(), journal, faults, live);
                    ran.add(fields(
                            "job", result.job().id(),
                            "asset", result.job().board(),
                            "state", result.job().state().value(),
                            "detail", result.detail()));
                }
            });
            if (context.asJson()) {
                context.say("worker finished", fields("recovered", recovered, "assigned", assigned, "ran", ran));
                return OK;
            }
            recovered.forEach(line -> context.echo("recovered " + line));
            assigned.forEach(line -> context.echo("assigned  " + line));
            ran.forEach(entry -> context.echo(
                    "ran       " + entry.get("job") + " " + entry.get("state") + ": " + entry.get("detail")));
            if (recovered.isEmpty() && assigned.isEmpty() && ran.isEmpty()) {
                context.echo("nothing to do");
            }
            return OK;
        }
    }
}
