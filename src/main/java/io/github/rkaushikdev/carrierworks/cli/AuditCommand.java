package io.github.rkaushikdev.carrierworks.cli;

import io.github.rkaushikdev.carrierworks.persistence.AuditRepository;
import io.github.rkaushikdev.carrierworks.services.Audit;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code fpga audit}: the audit trail from the command line, which is where it gets read.
 *
 * <p>Somebody asks what happened to a board overnight, and the answer is one command with a board
 * tag in it. The filters combine, so the follow up question, which is always what else that script
 * touched, is the same command with the actor instead.
 *
 * <p>Times are printed to the second in UTC. The bench writes everything in UTC, and converting for
 * display here would mean two different clocks in the same answer, since the detail lines carry
 * their own timestamps.
 */
@Command(name = "audit", description = "Who did what, newest first.")
final class AuditCommand extends BenchCommand {

    @Option(names = "--subject", paramLabel = "TEXT", description = "one board, image or lease")
    private Optional<String> subject = Optional.empty();

    @Option(names = "--actor", paramLabel = "TEXT", description = "one person or script")
    private Optional<String> actor = Optional.empty();

    @Option(names = "--action", paramLabel = "TEXT", description = "one kind of thing done")
    private Optional<String> action = Optional.empty();

    @Option(names = "--hours", paramLabel = "INTEGER", description = "nothing older than this many hours")
    private int hours;

    @Option(names = "--limit", paramLabel = "INTEGER", description = "how many lines at most")
    private int limit = Audit.DEFAULT_LIMIT;

    @Override
    int run(Context context) {
        if (limit < 1) {
            throw new UsageError("ask for at least one line");
        }
        AuditRepository.Search search = AuditRepository.Search.everything().limit(limit);
        search = subject.map(search::subject).orElse(search);
        search = actor.map(search::actor).orElse(search);
        search = action.map(search::action).orElse(search);
        if (hours != 0) {
            search = search.since(context.now().minus(Duration.ofHours(hours)));
        }
        AuditRepository.Search narrowed = search;
        List<Map<String, Object>> rows = context.apply(unit -> Audit.search(unit, narrowed).stream()
                .map(entry -> fields(
                        "at", Times.iso(entry.at(), Times.Precision.SECONDS),
                        "actor", entry.actor(),
                        "action", entry.action(),
                        "subject", entry.subject().isEmpty() ? "-" : entry.subject(),
                        "detail", entry.detail().isEmpty() ? "-" : entry.detail()))
                .toList());
        context.emit(rows, "at", "actor", "action", "subject", "detail");
        return OK;
    }
}
