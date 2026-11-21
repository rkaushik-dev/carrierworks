package io.github.rkaushikdev.carrierworks.cli;

import io.github.rkaushikdev.carrierworks.jobs.JobMode;
import io.github.rkaushikdev.carrierworks.jobs.ProgramJob;
import io.github.rkaushikdev.carrierworks.jobs.RunResult;
import io.github.rkaushikdev.carrierworks.services.Execution;
import io.github.rkaushikdev.carrierworks.services.Programming;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code fpga job}: programming jobs from the command line, including the dry run people use.
 *
 * <p>The run command is the one that touches hardware, so it defaults to not touching any: without
 * {@code --live} it runs the whole job against the simulator and the fake flash, which checks the
 * gates, the image, the chain the board should present and the plan, and changes nothing. That is
 * what somebody does before letting a golden image anywhere near a board at four in the afternoon.
 */
@Command(
        name = "job",
        description = "Getting images onto boards.",
        subcommands = {
            JobCommands.Cancel.class,
            JobCommands.ListJobs.class,
            JobCommands.RunJob.class,
            JobCommands.Submit.class
        })
final class JobCommands {

    private JobCommands() {}

    /** Put a programming job on the queue. */
    @Command(name = "submit", description = "Put a programming job on the queue.")
    static final class Submit extends BenchCommand {

        @Option(names = "--board", paramLabel = "TEXT", required = true, description = "which board")
        private String tag;

        @Option(names = "--image", paramLabel = "TEXT", required = true, description = "which image")
        private String digest;

        @Option(names = "--mode", paramLabel = "TEXT", description = "jtag, flash-update or flash-golden")
        private String mode = JobMode.JTAG.value();

        @Option(names = "--by", paramLabel = "TEXT", required = true, description = "who is asking")
        private String requestedBy;

        @Option(names = "--no-verify", description = "skip the read back after a flash write")
        private boolean noVerify;

        @Override
        int run(Context context) {
            ProgramJob job = context.apply(unit -> Programming.submit(
                    unit, tag, digest, JobMode.exactly(mode), requestedBy, context.now(), !noVerify));
            context.say(
                    job.id() + " queued: " + job.mode().value() + " " + job.shortDigest() + " on " + job.board(),
                    fields(
                            "job",
                            job.id(),
                            "board",
                            job.board(),
                            "mode",
                            job.mode().value()));
            return OK;
        }
    }

    /**
     * What has been asked of the bench.
     *
     * <p>Narrowed to one board, the listing is every job for it whatever its state.
     */
    @Command(name = "list", description = "What has been asked of the bench.")
    static final class ListJobs extends BenchCommand {

        @Option(names = "--state", paramLabel = "TEXT", description = "queued, running, passed, failed")
        private Optional<String> state = Optional.empty();

        @Option(names = "--board", paramLabel = "TEXT", description = "one board")
        private Optional<String> tag = Optional.empty();

        @Override
        int run(Context context) {
            Optional<String> board = tag.filter(name -> !name.isEmpty());
            List<Map<String, Object>> rows = context.apply(unit -> {
                List<ProgramJob> found = board.isPresent()
                        ? Programming.forBoard(unit, board.get())
                        : unit.boards().all().stream()
                                .flatMap(each -> unit.jobs().forBoard(each.tag()).stream())
                                .filter(job ->
                                        state.isEmpty() || job.state().value().equals(state.get()))
                                .toList();
                return found.stream()
                        .map(job -> fields(
                                "job", job.id(),
                                "board", job.board(),
                                "mode", job.mode().value(),
                                "image", job.shortDigest(),
                                "state", job.state().value(),
                                "attempts", job.attempts(),
                                "error", job.error().isEmpty() ? "-" : job.error()))
                        .toList();
            });
            context.emit(rows, "job", "board", "mode", "image", "state", "attempts", "error");
            return OK;
        }
    }

    /** Run one job, the next ready one by default, and say what happened. */
    @Command(name = "run", description = "Run one job, the next ready one by default, and say what happened.")
    static final class RunJob extends BenchCommand {

        @Parameters(paramLabel = "JOB_ID", arity = "0..1")
        private Optional<String> jobId = Optional.empty();

        @Option(names = "--live", description = "use the real cable rather than the simulator")
        private boolean live;

        @Override
        int run(Context context) {
            Instant now = context.now();
            Optional<RunResult> ran = context.apply(unit -> {
                Optional<String> named = jobId.filter(id -> !id.isEmpty());
                Optional<ProgramJob> job =
                        named.isPresent() ? Optional.of(unit.jobs().get(named.get())) : Execution.nextReady(unit, now);
                return job.map(ready -> Execution.runJob(unit, ready, now, context.settings(), live));
            });
            if (ran.isEmpty()) {
                context.say("nothing is ready to run", fields("ran", null));
                return OK;
            }
            RunResult result = ran.get();
            context.say(
                    result.describe(),
                    fields(
                            "job", result.job().id(),
                            "state", result.job().state().value(),
                            "detail", result.detail(),
                            "board_faulted", result.faultedBoard()));
            return OK;
        }
    }

    /** Take a job off the queue. */
    @Command(name = "cancel", description = "Take a job off the queue.")
    static final class Cancel extends BenchCommand {

        @Parameters(paramLabel = "JOB_ID")
        private String jobId;

        @Option(names = "--reason", paramLabel = "TEXT", description = "why it is being dropped")
        private String reason = Programming.CANCELLED;

        @Override
        int run(Context context) {
            ProgramJob cancelled = context.apply(unit -> Programming.cancel(unit, jobId, context.now(), reason));
            context.say(
                    cancelled.id() + " cancelled",
                    fields("job", cancelled.id(), "state", cancelled.state().value(), "reason", reason));
            return OK;
        }
    }
}
