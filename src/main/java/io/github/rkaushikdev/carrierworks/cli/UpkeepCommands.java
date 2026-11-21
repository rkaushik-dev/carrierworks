package io.github.rkaushikdev.carrierworks.cli;

import io.github.rkaushikdev.carrierworks.inventory.MaintenanceRecord;
import io.github.rkaushikdev.carrierworks.services.CalibrationState;
import io.github.rkaushikdev.carrierworks.services.Upkeep;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code fpga upkeep}: the maintenance log from the command line.
 *
 * <p>Somebody finishes work on a board and has to do two things: write down what they did, and put
 * the board back. The done command does both, because the board that stays in maintenance for a week
 * is nearly always the one where the second command got forgotten.
 *
 * <p>The due command is the one to run on a Monday. It lists what has run out of calibration, and a
 * board that has never been calibrated counts as run out rather than as unknown.
 */
@Command(
        name = "upkeep",
        description = "Visits, calibration and getting boards back in service.",
        subcommands = {
            UpkeepCommands.Calibration.class,
            UpkeepCommands.Done.class,
            UpkeepCommands.Due.class,
            UpkeepCommands.History.class,
            UpkeepCommands.Log.class
        })
final class UpkeepCommands {

    private UpkeepCommands() {}

    /** The day a moment falls on, in UTC. */
    private static String day(Instant at) {
        return LocalDate.ofInstant(at, ZoneOffset.UTC).toString();
    }

    /** The options a visit is written down with. */
    abstract static class Visit extends BenchCommand {

        @Parameters(paramLabel = "TAG")
        String tag;

        @Option(names = "--kind", paramLabel = "TEXT", description = "one of calibration, inspection, repair, upgrade")
        String kind = Upkeep.REPAIR;

        @Option(names = "--by", paramLabel = "TEXT", required = true, description = "who did the work")
        String by;

        @Option(names = "--notes", paramLabel = "TEXT", description = "what was done")
        String notes = "";

        @Option(names = "--cleared", paramLabel = "INTEGER", description = "how many faults this fixed")
        int cleared;
    }

    /** Write down a visit without moving the board. */
    @Command(name = "log", description = "Write down a visit without moving the board.")
    static final class Log extends Visit {

        @Override
        int run(Context context) {
            MaintenanceRecord record =
                    context.apply(unit -> Upkeep.logVisit(unit, tag, kind, context.now(), by, notes, cleared));
            context.say(
                    record.describe(),
                    fields(
                            "board", record.board(),
                            "kind", record.kind().value(),
                            "by", record.by(),
                            "at", record.at()));
            return OK;
        }
    }

    /** Log the visit and put the board back in service. */
    @Command(name = "done", description = "Log the visit and put the board back in service.")
    static final class Done extends Visit {

        @Override
        int run(Context context) {
            MaintenanceRecord record =
                    context.apply(unit -> Upkeep.returnToService(unit, tag, context.now(), by, kind, notes, cleared));
            context.say(
                    record.describe() + ", " + tag + " is available",
                    fields("board", record.board(), "kind", record.kind().value(), "state", "available"));
            return OK;
        }
    }

    /** Every visit, to one board or to the whole bench. */
    @Command(name = "history", description = "Every visit, to one board or to the whole bench.")
    static final class History extends BenchCommand {

        @Parameters(paramLabel = "TAG", arity = "0..1")
        private Optional<String> tag = Optional.empty();

        @Override
        int run(Context context) {
            List<Map<String, Object>> rows = context.apply(unit -> {
                List<MaintenanceRecord> found;
                if (tag.isEmpty()) {
                    found = unit.maintenance().all();
                } else {
                    unit.boards().get(tag.get());
                    found = unit.maintenance().forBoard(tag.get());
                }
                return found.stream()
                        .map(record -> fields(
                                "asset", record.board(),
                                "kind", record.kind().value(),
                                "at", day(record.at()),
                                "by", record.by(),
                                "cleared", record.faultsCleared(),
                                "notes", record.notes().isEmpty() ? "-" : record.notes()))
                        .toList();
            });
            context.emit(rows, "asset", "kind", "at", "by", "cleared", "notes");
            return OK;
        }
    }

    /** Where one board stands on calibration. */
    @Command(name = "calibration", description = "Where one board stands on calibration.")
    static final class Calibration extends BenchCommand {

        @Parameters(paramLabel = "TAG")
        private String tag;

        @Override
        int run(Context context) {
            CalibrationState state = context.apply(unit -> Upkeep.calibration(unit, tag, context.now()));
            context.say(
                    state.describe(),
                    fields(
                            "board", state.board(),
                            "required", state.required(),
                            "last_at", state.lastAt(),
                            "due_at", state.dueAt(),
                            "valid", state.valid(),
                            "never", state.never()));
            return OK;
        }
    }

    /** Every board whose calibration has run out or never happened. */
    @Command(name = "due", description = "Every board whose calibration has run out or never happened.")
    static final class Due extends BenchCommand {

        @Override
        int run(Context context) {
            List<Map<String, Object>> rows = context.apply(unit -> Upkeep.overdue(unit, context.now()).stream()
                    .map(state -> fields(
                            "asset", state.board(),
                            "last", state.lastAt().map(UpkeepCommands::day).orElse("never"),
                            "due", state.dueAt().map(UpkeepCommands::day).orElse("-")))
                    .toList());
            context.emit(rows, "asset", "last", "due");
            return OK;
        }
    }
}
