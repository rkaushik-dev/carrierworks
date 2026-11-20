package io.github.rkaushikdev.carrierworks;

import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.Chains;
import io.github.rkaushikdev.carrierworks.jobs.FakeFlash;
import io.github.rkaushikdev.carrierworks.jobs.SpiFlashDriver;
import io.github.rkaushikdev.carrierworks.jtag.AdapterUnit;
import io.github.rkaushikdev.carrierworks.jtag.ChainSimulator;
import io.github.rkaushikdev.carrierworks.jtag.ScanChain;
import io.github.rkaushikdev.carrierworks.jtag.ScanSession;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Where a job gets its cable from, and what stands in when there is none.
 *
 * <p>There is no real driver in this tree. Every adapter the lab owns speaks to a vendor library
 * that cannot be installed on the machine the bench runs on, so the drivers live outside it and
 * register themselves here at start up. What ships is the simulator, and it is not a placeholder:
 * the bench runs against it in the dry run mode people use to check a job before letting it near a
 * board, and it is what the tests exercise.
 *
 * <p>Registration is by adapter model rather than by board. Two boards with the same cable use the
 * same driver, and a board with no cable has no driver at all, which is the honest answer rather
 * than a fake one. The registrations are shared by the whole process and safe to make from any
 * thread.
 */
public final class Drivers {

    /** What the bench calls the stand in it uses when a job is not to touch real hardware. */
    public static final String SIMULATED = "simulated";

    private static final Map<String, Function<Board, ScanSession>> TRANSPORTS = new ConcurrentHashMap<>();
    private static final Map<String, Function<Board, SpiFlashDriver>> FLASHES = new ConcurrentHashMap<>();

    private Drivers() {}

    /** Point an adapter model, however it is written, at whatever drives its JTAG chain. */
    public static void registerTransport(String model, Function<Board, ScanSession> factory) {
        TRANSPORTS.put(Ids.slug(model), Objects.requireNonNull(factory, "factory"));
    }

    /** Point an adapter model, however it is written, at whatever drives the flash behind it. */
    public static void registerFlashDriver(String model, Function<Board, SpiFlashDriver> factory) {
        FLASHES.put(Ids.slug(model), Objects.requireNonNull(factory, "factory"));
    }

    /** {@link #sessionFor(Board, boolean)} through the board's real cable. */
    public static ScanSession sessionFor(Board board) {
        return sessionFor(board, false);
    }

    /**
     * The session to talk to a board through.
     *
     * <p>A simulated session is against a simulated chain that matches the board on file, running
     * at the board's clock ceiling. The simulator answers with the idcodes the inventory says the
     * board has, so a job run against it exercises everything above the transport and nothing below
     * it. A board whose row is wrong will still verify here, which is exactly the limit of what a
     * simulation can tell anybody.
     *
     * @param simulated whether to use the simulator rather than the board's cable
     * @throws NotFound when the board has no cable, or nothing is registered for its model
     */
    public static ScanSession sessionFor(Board board, boolean simulated) {
        if (simulated) {
            ScanChain chain = Chains.chainFor(board);
            return new ScanSession(ChainSimulator.forChain(chain), chain, new Frequency(board.maxTckHz()));
        }
        AdapterUnit adapter = cable(board);
        Function<Board, ScanSession> factory = TRANSPORTS.get(adapter.model());
        if (factory == null) {
            throw new NotFound("transport driver", adapter.model());
        }
        return factory.apply(board);
    }

    /** {@link #flashFor(Board, boolean)} through the board's real cable. */
    public static SpiFlashDriver flashFor(Board board) {
        return flashFor(board, false);
    }

    /**
     * The flash driver for a board, or a simulated part.
     *
     * <p>A simulated part is a fresh, erased copy of the flash the board carries.
     *
     * @param simulated whether to use a simulated part rather than the board's cable
     * @throws NotFound when the board has no cable, its cable cannot write flash, or nothing is
     *     registered for its model
     */
    public static SpiFlashDriver flashFor(Board board, boolean simulated) {
        if (simulated) {
            return new FakeFlash(board.flashSpec());
        }
        AdapterUnit adapter = cable(board);
        if (!adapter.spec().canProgramFlash()) {
            throw new NotFound("flash driver", adapter.model());
        }
        Function<Board, SpiFlashDriver> factory = FLASHES.get(adapter.model());
        if (factory == null) {
            throw new NotFound("flash driver", adapter.model());
        }
        return factory.apply(board);
    }

    /** Which adapter models have a driver of either kind, sorted, which is what a startup log wants. */
    public static List<String> registered() {
        TreeSet<String> models = new TreeSet<>(TRANSPORTS.keySet());
        models.addAll(FLASHES.keySet());
        return List.copyOf(models);
    }

    /** Drop every registration, which is what a test does between cases. */
    public static void forgetAll() {
        TRANSPORTS.clear();
        FLASHES.clear();
    }

    private static AdapterUnit cable(Board board) {
        return board.adapter().orElseThrow(() -> new NotFound("cable", board.tag()));
    }
}
