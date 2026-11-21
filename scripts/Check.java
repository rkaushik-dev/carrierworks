package io.github.rkaushikdev.carrierworks.scripts;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Every gate the bench has to pass, in one command, in the same order as CI.
 *
 * <p>Written because the checks kept being run in different orders by different people and by the
 * workflow, and the order matters: a formatting change that lands after the compile has to be
 * reviewed again, and a test suite that runs before the compiler's lint spends minutes proving
 * something a raw type would have failed in seconds.
 *
 * <p>Fast gates first, then the compiler, then the suite. Anything that fails stops the rest unless
 * {@code --keep-going} says otherwise, which is what somebody wants locally and never what CI
 * wants. It needs nothing but a JDK, and runs from anywhere in the checkout:
 *
 * <pre>java scripts/Check.java [--keep-going] [gate ...]</pre>
 */
public final class Check {

    /** The gates, fastest first: the order they run in, whatever order they are asked for in. */
    public static final List<Gate> GATES = List.of(
            new Gate("format", List.of("spotless:check")),
            new Gate("compile", List.of("test-compile")),
            new Gate("tests", List.of("verify", "-Dspotless.check.skip=true")));

    static final String USAGE = "usage: Check.java [-h] [--keep-going] [gates ...]";

    static final String HELP = USAGE + "\n\n"
            + "run the bench checks\n\n"
            + "positional arguments:\n"
            + "  gates         which gates to run, default all of them\n\n"
            + "options:\n"
            + "  -h, --help    show this help message and exit\n"
            + "  --keep-going  run every gate even after one fails\n";

    private Check() {}

    /**
     * One check, and the build goals that run it.
     *
     * @param name what it is called on the command line and in the report
     * @param goals what the build is asked to do for it
     */
    public record Gate(String name, List<String> goals) {

        public Gate {
            goals = List.copyOf(goals);
        }

        /** The command line: the repository's own build wrapper, in batch mode, then the goals. */
        public List<String> argv() {
            List<String> argv = new ArrayList<>(List.of(wrapper().toString(), "-B"));
            argv.addAll(goals);
            return List.copyOf(argv);
        }
    }

    /**
     * What one gate came to.
     *
     * @param code the build's exit status, zero for a pass
     * @param seconds how long it took
     */
    public record Outcome(int code, double seconds) {}

    /** Runs one gate. The real one starts the build; a test hands in one that does not. */
    @FunctionalInterface
    public interface Runner {

        /** Run the gate and say how it went. */
        Outcome run(Gate gate);
    }

    /** A gate somebody asked for by a name no gate has. */
    public static final class UnknownGate extends IllegalArgumentException {

        UnknownGate(List<String> names) {
            super("no such gate: " + String.join(", ", names));
        }
    }

    /** The checkout this runs in: the nearest directory up from here holding the build and its wrapper. */
    public static Path root() {
        Path here = Path.of("").toAbsolutePath();
        for (Path directory = here; directory != null; directory = directory.getParent()) {
            if (Files.isRegularFile(directory.resolve("pom.xml")) && Files.isRegularFile(directory.resolve("mvnw"))) {
                return directory;
            }
        }
        throw new IllegalStateException("not inside the checkout: " + here);
    }

    /** The build wrapper at the root of the checkout, the batch file on Windows. */
    public static Path wrapper() {
        boolean windows =
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        return root().resolve(windows ? "mvnw.cmd" : "mvnw");
    }

    /** Run one gate from the root of the checkout, on this terminal, and time it. */
    public static Outcome execute(Gate gate) {
        long started = System.nanoTime();
        try {
            Process build = new ProcessBuilder(gate.argv())
                    .directory(root().toFile())
                    .inheritIO()
                    .start();
            int code = build.waitFor();
            return new Outcome(code, (System.nanoTime() - started) / 1e9);
        } catch (IOException failure) {
            throw new UncheckedIOException("could not start " + gate.name(), failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Outcome(130, (System.nanoTime() - started) / 1e9);
        }
    }

    /**
     * The gates asked for, in the order they are declared in; all of them when none are named.
     *
     * @throws UnknownGate when a name is not a gate's
     */
    public static List<Gate> select(List<String> names) {
        if (names.isEmpty()) {
            return GATES;
        }
        Set<String> known = GATES.stream().map(Gate::name).collect(Collectors.toSet());
        List<String> unknown =
                names.stream().filter(name -> !known.contains(name)).toList();
        if (!unknown.isEmpty()) {
            throw new UnknownGate(unknown);
        }
        return GATES.stream().filter(gate -> names.contains(gate.name())).toList();
    }

    /**
     * Run the gates a command line asks for, reporting each on {@code out}.
     *
     * @return zero when every gate passed, one when any failed or a gate was unknown, two for a
     *     command line that could not be read
     */
    public static int check(List<String> args, Runner runner, PrintStream out, PrintStream err) {
        boolean keepGoing = false;
        List<String> names = new ArrayList<>();
        for (String arg : args) {
            switch (arg) {
                case "-h", "--help" -> {
                    out.print(HELP);
                    return 0;
                }
                case "--keep-going" -> keepGoing = true;
                default -> {
                    if (arg.startsWith("-")) {
                        err.print(USAGE + "\nCheck.java: error: unrecognized arguments: " + arg + "\n");
                        return 2;
                    }
                    names.add(arg);
                }
            }
        }
        List<Gate> gates;
        try {
            gates = select(names);
        } catch (UnknownGate unknown) {
            err.println(unknown.getMessage());
            return 1;
        }
        List<String> failures = new ArrayList<>();
        for (Gate gate : gates) {
            Outcome outcome = runner.run(gate);
            String status = outcome.code() == 0 ? "ok" : "failed (" + outcome.code() + ")";
            out.println(String.format(Locale.ROOT, "%-7s %s in %.1fs", gate.name(), status, outcome.seconds()));
            out.flush();
            if (outcome.code() != 0) {
                failures.add(gate.name());
                if (!keepGoing) {
                    break;
                }
            }
        }
        if (failures.isEmpty()) {
            return 0;
        }
        err.println("failed: " + String.join(", ", failures));
        return 1;
    }

    /** Run the gates the command line names against the real build, and exit with the result. */
    public static void main(String... args) {
        System.exit(check(List.of(args), Check::execute, System.out, System.err));
    }
}
