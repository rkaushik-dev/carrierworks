package io.github.rkaushikdev.carrierworks.cli;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.PduChannel;
import io.github.rkaushikdev.carrierworks.PowerFault;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardState;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import io.github.rkaushikdev.carrierworks.power.FakePdu;
import io.github.rkaushikdev.carrierworks.power.Interlock;
import io.github.rkaushikdev.carrierworks.power.PduDriver;
import io.github.rkaushikdev.carrierworks.power.PowerController;
import io.github.rkaushikdev.carrierworks.power.PowerTree;
import io.github.rkaushikdev.carrierworks.power.SequencePlan;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code fpga power}: outlets and rails from the command line, with the interlocks in front.
 *
 * <p>Two different things are called power here and they are worth keeping apart. The outlet is the
 * thing the distribution unit switches, and it is all or nothing. The rails are what the carrier
 * does after the outlet comes up, in the order the datasheets want, and the bench only ever plans
 * those: no board in this lab exposes rail control to software.
 *
 * <p>So the on and cycle commands really switch an outlet, and the plan command prints the rail
 * sequence the carrier will run through on its own. Both go through the same interlocks, because the
 * reason not to touch a board is the same either way: somebody has their hands in it, or somebody
 * else is using it.
 *
 * <p>Without {@code --live} every switch goes to a fake unit that answers the same way and changes
 * nothing real. With it, the switch goes to the driver registered for the board's distribution unit.
 */
@Command(
        name = "power",
        description = "Outlets, rails and the interlocks in front of both.",
        subcommands = {
            PowerCommands.Cycle.class,
            PowerCommands.PowerOff.class,
            PowerCommands.PowerOn.class,
            PowerCommands.ShowPlan.class,
            PowerCommands.ShowOutlet.class,
            PowerCommands.ShowTree.class
        })
final class PowerCommands {

    private static final Map<String, PduDriver> DRIVERS = new ConcurrentHashMap<>();

    private PowerCommands() {}

    /** Point a rack's distribution unit at whatever drives it. */
    static void registerPdu(String name, PduDriver driver) {
        DRIVERS.put(name, driver);
    }

    /** Forget every registered unit. */
    static void forgetPdus() {
        DRIVERS.clear();
    }

    /**
     * The unit this board hangs off, or a fake one that answers the same.
     *
     * @throws PowerFault when the board is on no switched outlet, or nothing drives its unit
     */
    static PduDriver driverFor(Board board, boolean simulated) {
        PduChannel outlet = board.pdu()
                .orElseThrow(() ->
                        new PowerFault("that board is not on a switched outlet", Details.of("board", board.tag())));
        if (simulated) {
            return new FakePdu().withOutletOn(outlet);
        }
        PduDriver found = DRIVERS.get(outlet.pdu());
        if (found == null) {
            throw new PowerFault("no driver for that distribution unit", Details.of("pdu", outlet.pdu()));
        }
        return found;
    }

    /**
     * What would stop somebody switching this board, and why.
     *
     * <p>Maintenance is the hard one and it is deliberately not overridable from here: a board in
     * maintenance is a board with a person at it, and the way to power it is to take it out of
     * maintenance first, which leaves a line in the audit trail with a name on it.
     */
    static List<Interlock> interlocks(BoardState state, Optional<Lease> lease, String holder) {
        boolean heldBySomebodyElse =
                lease.map(held -> !held.holder().equals(holder)).orElse(false);
        return List.of(
                new Interlock(
                        "maintenance", state.state() == BoardStatus.MAINTENANCE, "somebody is working on the board"),
                new Interlock(
                        "lease",
                        heldBySomebodyElse,
                        lease.map(held -> held.holder() + " is using it").orElse("")));
    }

    /** A board and what stands in front of switching it. */
    private record Locked(Board board, List<Interlock> locks) {

        /** What is engaged, as a line each. */
        List<String> engaged() {
            return locks.stream()
                    .filter(Interlock::engaged)
                    .map(Interlock::describe)
                    .toList();
        }

        /**
         * @throws PowerFault when something is engaged and the switch is not forced
         */
        void refuseUnless(boolean force) {
            if (!engaged().isEmpty() && !force) {
                throw new PowerFault(
                        "that board is interlocked",
                        Details.of("board", board.tag(), "reasons", String.join("; ", engaged())));
            }
        }
    }

    private static Locked locked(Context context, String tag, String holder) {
        return context.apply(unit -> new Locked(
                unit.boards().get(tag),
                interlocks(unit.boards().state(tag), unit.leases().forBoard(tag, context.now()), holder)));
    }

    /** The rails on this carrier, in the order they come up. */
    @Command(name = "tree", description = "The rails on this carrier, in the order they come up.")
    static final class ShowTree extends BenchCommand {

        @Parameters(paramLabel = "TAG")
        private String tag;

        @Override
        int run(Context context) {
            Board board = context.apply(unit -> unit.boards().get(tag));
            List<Map<String, Object>> rows = PowerTree.forModel(board.model()).orderUp().stream()
                    .map(step -> fields(
                            "rail", step.name(),
                            "mv", step.rail().nominalMv(),
                            "max_ma", step.rail().maxCurrentMa(),
                            "after", step.dependsOn().isEmpty() ? "-" : String.join(",", step.dependsOn()),
                            "settle_ms", step.settleMs()))
                    .toList();
            context.emit(rows, "rail", "mv", "max_ma", "after", "settle_ms");
            return OK;
        }
    }

    /** What the carrier will do to its rails, and what would stop it. */
    @Command(name = "plan", description = "What the carrier will do to its rails, and what would stop it.")
    static final class ShowPlan extends BenchCommand {

        @Parameters(paramLabel = "TAG")
        private String tag;

        @Option(names = "--down", description = "the shutdown order instead")
        private boolean goingDown;

        @Option(names = "--holder", paramLabel = "TEXT", description = "who is asking, for the lease interlock")
        private String holder = "cli";

        @Override
        int run(Context context) {
            Locked locked = locked(context, tag, holder);
            PowerTree tree = PowerTree.forModel(locked.board().model());
            SequencePlan plan = goingDown
                    ? SequencePlan.powerDown(tree, locked.locks())
                    : SequencePlan.powerUp(tree, locked.locks());
            if (context.asJson()) {
                context.emit(
                        plan.steps().stream()
                                .map(step -> fields(
                                        "action", step.action().value(), "rail", step.rail(), "ms", step.millis()))
                                .toList(),
                        "action",
                        "rail",
                        "ms");
                return OK;
            }
            context.echo(plan.describe());
            plan.steps().forEach(step -> context.echo("  " + step.describe()));
            return OK;
        }
    }

    /** Whether the outlet is on, and what it is drawing. */
    @Command(name = "show", description = "Whether the outlet is on, and what it is drawing.")
    static final class ShowOutlet extends BenchCommand {

        @Parameters(paramLabel = "TAG")
        private String tag;

        @Option(names = "--live", description = "ask the real distribution unit")
        private boolean live;

        @Option(names = "--holder", paramLabel = "TEXT", description = "who is asking, for the lease interlock")
        private String holder = "cli";

        @Override
        int run(Context context) {
            Locked locked = locked(context, tag, holder);
            Board board = locked.board();
            PduDriver driver = driverFor(board, !live);
            boolean on = new PowerController(driver).isOn(board);
            PduChannel outlet = board.pdu().orElseThrow();
            int drawn = driver.currentMa(outlet);
            context.say(
                    tag + " is " + (on ? "on" : "off") + " at " + outlet + ", drawing " + drawn + "mA",
                    fields(
                            "board",
                            tag,
                            "outlet",
                            outlet.toString(),
                            "pdu",
                            driver.name(),
                            "on",
                            on,
                            "current_ma",
                            drawn,
                            "blocked_by",
                            locked.engaged()));
            return OK;
        }
    }

    /** The options every switching command takes. */
    abstract static class Switch extends BenchCommand {

        @Parameters(paramLabel = "TAG")
        String tag;

        @Option(names = "--live", description = "switch the real outlet")
        boolean live;

        @Option(names = "--force", description = "switch it even though something is engaged")
        boolean force;

        @Option(names = "--holder", paramLabel = "TEXT", description = "who is asking, for the lease interlock")
        String holder = "cli";

        int flip(Context context, boolean on) {
            Locked locked = locked(context, tag, holder);
            locked.refuseUnless(force);
            PowerController controller = new PowerController(driverFor(locked.board(), !live));
            boolean changed = on ? controller.powerOn(locked.board()) : controller.powerOff(locked.board());
            String word = on ? "on" : "off";
            context.say(
                    changed ? tag + " " + word : tag + " was already " + word,
                    fields("board", tag, "on", on, "changed", changed, "blocked_by", locked.engaged()));
            return OK;
        }
    }

    /** Switch a board's outlet on. */
    @Command(name = "on", description = "Switch a board's outlet on.")
    static final class PowerOn extends Switch {

        @Override
        int run(Context context) {
            return flip(context, true);
        }
    }

    /** Switch a board's outlet off. */
    @Command(name = "off", description = "Switch a board's outlet off.")
    static final class PowerOff extends Switch {

        @Override
        int run(Context context) {
            return flip(context, false);
        }
    }

    /** Off, wait, on, which is what fixes a board that stopped answering. */
    @Command(name = "cycle", description = "Off, wait, on, which is what fixes a board that stopped answering.")
    static final class Cycle extends BenchCommand {

        @Parameters(paramLabel = "TAG")
        private String tag;

        @Option(names = "--off-ms", paramLabel = "INTEGER", description = "how long to leave it down")
        private Optional<Integer> offMs = Optional.empty();

        @Option(names = "--live", description = "switch the real outlet")
        private boolean live;

        @Option(names = "--force", description = "cycle it even though something is engaged")
        private boolean force;

        @Option(names = "--holder", paramLabel = "TEXT", description = "who is asking, for the lease interlock")
        private String holder = "cli";

        @Override
        int run(Context context) {
            Locked locked = locked(context, tag, holder);
            locked.refuseUnless(force);
            PowerController controller = new PowerController(driverFor(locked.board(), !live));
            int waited = offMs.map(ms -> controller.cycle(locked.board(), ms))
                    .orElseGet(() -> controller.cycle(locked.board()));
            context.say(
                    tag + " cycled, down for " + waited + "ms",
                    fields("board", tag, "off_ms", waited, "blocked_by", locked.engaged()));
            return OK;
        }
    }
}
