package io.github.rkaushikdev.carrierworks.cli;

import io.github.rkaushikdev.carrierworks.Drivers;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.inventory.ChainCheck;
import io.github.rkaushikdev.carrierworks.inventory.Chains;
import io.github.rkaushikdev.carrierworks.jtag.BitVector;
import io.github.rkaushikdev.carrierworks.jtag.ChainDifference;
import io.github.rkaushikdev.carrierworks.jtag.ScanChain;
import io.github.rkaushikdev.carrierworks.jtag.ScanSession;
import io.github.rkaushikdev.carrierworks.jtag.TapPosition;
import io.github.rkaushikdev.carrierworks.services.Inventory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code fpga chain}: scanning a board's chain and acting on what came back.
 *
 * <p>This is the command somebody runs when a board has started failing and nobody knows why yet. It
 * scans, compares against what the inventory says should be on the header, and prints the
 * difference in the terms the difference is actually in: a missing answer is a cable, a wrong
 * idcode in the right place is the wrong part fitted, and the same idcodes in the wrong order is the
 * wrong header.
 *
 * <p>Nothing moves the board unless {@code --apply} is given. A scan that quietly sent a board to
 * maintenance would make people stop scanning, and a scan is the cheapest diagnostic on the bench.
 */
@Command(
        name = "chain",
        description = "What is on the header, and what should be.",
        subcommands = {ChainCommands.Diagnose.class, ChainCommands.ScanChainCommand.class, ChainCommands.Show.class})
final class ChainCommands {

    private ChainCommands() {}

    private static Board board(Context context, String tag) {
        return context.apply(unit -> unit.boards().get(tag));
    }

    /** Scan one board, through the real cable or the simulator. */
    private static BitVector capture(Board board, boolean live) {
        try (ScanSession session = Drivers.sessionFor(board, !live)) {
            return session.scanIdcodes();
        }
    }

    /** Move the board where the check says it belongs, if anywhere, and say where. */
    private static Optional<BoardStatus> apply(Context context, ChainCheck check, String actor) {
        Optional<BoardStatus> state = check.nextState();
        state.ifPresent(target -> context.run(unit -> {
            if (target == BoardStatus.FAULTED) {
                Inventory.faultBoard(unit, check.board(), context.now(), check.describe(), actor);
            } else {
                Inventory.moveState(unit, check.board(), target, context.now(), check.describe(), actor);
            }
        }));
        return state;
    }

    /** The chain this board should present, controllers and all. */
    @Command(name = "show", description = "The chain this board should present, controllers and all.")
    static final class Show extends BenchCommand {

        @Parameters(paramLabel = "TAG")
        private String tag;

        @Override
        int run(Context context) {
            ScanChain chain = Chains.chainFor(board(context, tag));
            List<TapPosition> taps = chain.taps();
            List<Map<String, Object>> rows = IntStream.range(0, taps.size())
                    .mapToObj(index -> {
                        TapPosition tap = taps.get(index);
                        return fields(
                                "position", index,
                                "label", tap.label(),
                                "ir", tap.irLength(),
                                "idcode",
                                        tap.expectedIdcode()
                                                .map(idcode -> String.format(Locale.ROOT, "0x%08x", idcode))
                                                .orElse("-"),
                                "device",
                                        tap.device()
                                                .filter(name -> !name.isEmpty())
                                                .orElse("-"));
                    })
                    .toList();
            context.emit(rows, "position", "label", "ir", "idcode", "device");
            return OK;
        }
    }

    /**
     * Scan a board and say whether it answered with what it should have.
     *
     * <p>Exits {@value BenchCommand#NEEDS_ATTENTION} when it did not.
     */
    @Command(name = "scan", description = "Scan a board and say whether it answered with what it should have.")
    static final class ScanChainCommand extends BenchCommand {

        @Parameters(paramLabel = "TAG")
        private String tag;

        @Option(names = "--live", description = "use the real cable rather than the simulator")
        private boolean live;

        @Option(names = "--apply", description = "move the board if the scan says to")
        private boolean applyState;

        @Option(names = "--actor", paramLabel = "TEXT", description = "who is scanning")
        private String actor = "cli";

        @Override
        int run(Context context) {
            Board board = board(context, tag);
            BitVector captured = capture(board, live);
            ChainCheck check = Chains.reconcile(board, captured);
            Optional<BoardStatus> moved = applyState ? apply(context, check, actor) : Optional.empty();
            context.say(
                    moved.map(state -> check.describe() + " (" + tag + " -> " + state.value() + ")")
                            .orElse(check.describe()),
                    fields(
                            "board", tag,
                            "ok", check.ok(),
                            "expected", check.expected(),
                            "found", check.found(),
                            "retry_worthwhile", check.retryWorthwhile(),
                            "differences",
                                    check.differences().stream()
                                            .map(ChainDifference::describe)
                                            .toList(),
                            "moved_to", moved.map(BoardStatus::value)));
            return check.ok() ? OK : NEEDS_ATTENTION;
        }
    }

    /**
     * Explain a capture somebody took somewhere else.
     *
     * <p>The capture is the bit string a scan produced, most significant bit first, which is what the
     * analyser on the rig writes out. It is worth having as a separate command because the
     * interesting captures are the ones taken while the cable was still plugged into whatever caused
     * the problem. With {@code --file} the capture is read from the file named, or from standard
     * input when that name is {@code -}.
     */
    @Command(name = "diagnose", description = "Explain a capture somebody took somewhere else.")
    static final class Diagnose extends BenchCommand {

        @Parameters(index = "0", paramLabel = "TAG")
        private String tag;

        @Parameters(index = "1", paramLabel = "CAPTURE")
        private String capture;

        @Option(names = "--file", description = "read the capture out of a file")
        private boolean fromFile;

        @Override
        int run(Context context) {
            String text = fromFile ? read(context, capture).strip() : capture;
            Board board = board(context, tag);
            ChainCheck check = Chains.reconcile(board, BitVector.parse(text));
            context.say(
                    check.describe(),
                    fields(
                            "board", tag,
                            "ok", check.ok(),
                            "expected", check.expected(),
                            "found", check.found(),
                            "wiring", check.wiring(),
                            "would_move_to", check.nextState().map(BoardStatus::value)));
            return OK;
        }

        private static String read(Context context, String name) {
            try {
                if (name.equals("-")) {
                    return context.in().lines().collect(Collectors.joining("\n"));
                }
                return Files.readString(Path.of(name), StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
    }
}
