package io.github.rkaushikdev.carrierworks.cli;

import io.github.rkaushikdev.carrierworks.PduChannel;
import io.github.rkaushikdev.carrierworks.catalog.CardSpec;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardState;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.jtag.AdapterUnit;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import io.github.rkaushikdev.carrierworks.services.BenchFile;
import io.github.rkaushikdev.carrierworks.services.ImportOutcome;
import io.github.rkaushikdev.carrierworks.services.Inventory;
import io.github.rkaushikdev.carrierworks.util.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** {@code fpga boards}: what is on the bench, and moving it about. */
@Command(
        name = "boards",
        description = "What is on the bench.",
        subcommands = {
            BoardCommands.ExportBoards.class,
            BoardCommands.ImportBoards.class,
            BoardCommands.ListBoards.class,
            BoardCommands.ShowBoard.class,
            BoardCommands.SetState.class
        })
final class BoardCommands {

    private BoardCommands() {}

    private static String cards(Board board) {
        return board.fittedCards().stream().map(CardSpec::name).collect(Collectors.joining(","));
    }

    /** Every board, with the state it is in. */
    @Command(name = "list", description = "Every board, with the state it is in.")
    static final class ListBoards extends BenchCommand {

        @Option(names = "--available", description = "only boards nobody is using")
        private boolean available;

        @Option(names = "--rack", paramLabel = "TEXT", description = "one rack rather than the whole bench")
        private Optional<String> rack = Optional.empty();

        @Override
        int run(Context context) {
            List<Map<String, Object>> rows = context.apply(unit -> {
                List<Board> boards =
                        rack.isPresent() ? unit.boards().inRack(rack.get()) : Inventory.listBoards(unit, available);
                return boards.stream()
                        .map(board -> fields(
                                "asset", board.tag(),
                                "model", board.model(),
                                "device", board.device().device(),
                                "location", board.location().toString(),
                                "state",
                                        unit.boards().state(board.tag()).state().value(),
                                "cards", cards(board).isEmpty() ? "-" : cards(board)))
                        .toList();
            });
            context.emit(rows, "asset", "model", "device", "location", "state", "cards");
            return OK;
        }
    }

    /** One board, in as much detail as the bench has. */
    @Command(name = "show", description = "One board, in as much detail as the bench has.")
    static final class ShowBoard extends BenchCommand {

        @Parameters(paramLabel = "TAG")
        private String tag;

        private record Shown(Board board, BoardState state, Optional<Lease> held) {}

        @Override
        int run(Context context) {
            Shown shown = context.apply(unit -> new Shown(
                    unit.boards().get(tag),
                    unit.boards().state(tag),
                    unit.leases().forBoard(tag, context.now())));
            Board board = shown.board();
            Optional<String> heldBy = shown.held().map(Lease::holder);
            Optional<String> adapter = board.adapter().map(AdapterUnit::describe);
            Optional<String> pdu = board.pdu().map(PduChannel::toString);
            if (context.asJson()) {
                context.json(fields(
                        "asset", board.tag(),
                        "model", board.model(),
                        "revision", board.revision(),
                        "part", board.part().orderingCode(),
                        "device", board.device().device(),
                        "flash", board.flash(),
                        "location", board.location().toString(),
                        "cards",
                                board.fittedCards().stream().map(CardSpec::name).toList(),
                        "transceivers_free", board.transceiversFree(),
                        "adapter", adapter,
                        "pdu", pdu,
                        "state", shown.state().state().value(),
                        "state_reason", shown.state().reason(),
                        "held_by", heldBy));
                return OK;
            }
            context.echo(board.describe());
            context.echo("state    " + shown.state().describe());
            context.echo(
                    "held by  " + heldBy.filter(holder -> !holder.isEmpty()).orElse("nobody"));
            context.echo("cable    " + adapter.filter(text -> !text.isEmpty()).orElse("none"));
            context.echo("outlet   " + pdu.filter(text -> !text.isEmpty()).orElse("none"));
            return OK;
        }
    }

    /** Move a board's state. */
    @Command(name = "state", description = "Move a board's state.")
    static final class SetState extends BenchCommand {

        @Parameters(index = "0", paramLabel = "TAG")
        private String tag;

        @Parameters(index = "1", paramLabel = "STATE")
        private String state;

        @Option(names = "--reason", paramLabel = "TEXT", description = "why, which some states insist on")
        private String reason = "";

        @Option(names = "--actor", paramLabel = "TEXT", description = "who is doing it")
        private String actor = "cli";

        private record Moved(BoardState state, Optional<String> givenBack) {}

        @Override
        int run(Context context) {
            Moved moved = context.apply(unit -> {
                if (state.equals(BoardStatus.FAULTED.value())) {
                    Inventory.Faulted faulted = Inventory.faultBoard(
                            unit,
                            tag,
                            context.now(),
                            reason.isEmpty() ? "faulted from the command line" : reason,
                            actor);
                    return new Moved(faulted.state(), faulted.released().map(Lease::id));
                }
                return new Moved(
                        Inventory.moveState(unit, tag, BoardStatus.parse(state), context.now(), reason, actor),
                        Optional.empty());
            });
            String now = moved.state().state().value();
            String message = tag + " is now " + now;
            if (moved.givenBack().isPresent()) {
                message = message + ", " + moved.givenBack().get() + " released";
            }
            context.say(message, fields("board", tag, "state", now, "released", moved.givenBack()));
            return OK;
        }
    }

    /** Put every board in a bench file onto the bench. */
    @Command(name = "import", description = "Put every board in a bench file onto the bench.")
    static final class ImportBoards extends BenchCommand {

        @Parameters(paramLabel = "PATH", converter = ExistingFile.class)
        private Path path;

        @Option(names = "--replace", description = "overwrite boards the bench already has")
        private boolean replace;

        @Override
        int run(Context context) {
            String text = BenchFile.readFile(path);
            ImportOutcome outcome = context.apply(unit -> BenchFile.importBench(unit, text, context.now(), replace));
            context.say(
                    outcome.describe(),
                    fields("added", outcome.added(), "replaced", outcome.replaced(), "skipped", outcome.skipped()));
            return OK;
        }
    }

    /** Write the bench out in the shape the import command reads. */
    @Command(name = "export", description = "Write the bench out in the shape the import command reads.")
    static final class ExportBoards extends BenchCommand {

        @Option(names = "--into", paramLabel = "TEXT", description = "write to a file rather than to the terminal")
        private Optional<String> into = Optional.empty();

        @Override
        int run(Context context) {
            Map<String, Object> payload = context.apply(
                    unit -> BenchFile.exportBench(unit, context.settings().site()));
            String text = Json.writer().indent(2).textFallback().write(payload) + "\n";
            if (into.isEmpty()) {
                context.write(text);
                return OK;
            }
            try {
                Files.writeString(Path.of(into.get()), text, StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            int boards = payload.get("boards") instanceof List<?> written ? written.size() : 0;
            context.say(boards + " boards written to " + into.get(), fields("path", into.get(), "boards", boards));
            return OK;
        }
    }
}
