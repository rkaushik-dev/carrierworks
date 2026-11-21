package io.github.rkaushikdev.carrierworks.scripts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.scripts.Check.Gate;
import io.github.rkaushikdev.carrierworks.scripts.Check.Outcome;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The gate runner in {@code scripts/Check.java}: which gates, in which order, and when it stops. */
class CheckScriptTest {

    private final List<String> seen = new ArrayList<>();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private static List<String> names(List<Gate> gates) {
        return gates.stream().map(Gate::name).toList();
    }

    /** A build where only the format gate fails, noting each gate it is asked to run. */
    private Outcome formatFails(Gate gate) {
        seen.add(gate.name());
        return new Outcome(gate.name().equals("format") ? 1 : 0, 0.0);
    }

    private int check(Check.Runner runner, String... args) {
        return Check.check(
                List.of(args),
                runner,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @Test
    void theGatesRunFastOnesFirst() {
        assertThat(names(Check.GATES)).containsExactly("format", "compile", "tests");
    }

    @Test
    void everyGateRunsThroughTheRepositorysOwnWrapper() {
        Path wrapper = Check.wrapper();
        assertThat(wrapper.getParent()).isEqualTo(Check.root());
        assertThat(Files.isRegularFile(Check.root().resolve("pom.xml"))).isTrue();
        assertThat(Files.isRegularFile(wrapper)).isTrue();
        for (Gate gate : Check.GATES) {
            assertThat(gate.argv().subList(0, 2)).as(gate.name()).containsExactly(wrapper.toString(), "-B");
        }
    }

    @Test
    void noGatesAskedForMeansAllOfThem() {
        assertThat(Check.select(List.of())).isEqualTo(Check.GATES);
    }

    @Test
    void oneGateCanBePickedOut() {
        assertThat(names(Check.select(List.of("compile")))).containsExactly("compile");
    }

    @Test
    void gatesComeBackInDeclaredOrderNotTheOrderAskedFor() {
        assertThat(names(Check.select(List.of("tests", "format")))).containsExactly("format", "tests");
    }

    @Test
    void aGateNobodyHasIsRefused() {
        assertThatThrownBy(() -> Check.select(List.of("vibes")))
                .isInstanceOf(Check.UnknownGate.class)
                .hasMessage("no such gate: vibes");
    }

    @Test
    void aFailingGateStopsTheRun() {
        assertThat(check(this::formatFails)).isEqualTo(1);
        assertThat(seen).containsExactly("format");
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("failed: format");
    }

    @Test
    void keepGoingRunsTheRest() {
        assertThat(check(this::formatFails, "--keep-going")).isEqualTo(1);
        assertThat(seen).containsExactly("format", "compile", "tests");
    }

    @Test
    void aCleanRunIsZero() {
        assertThat(check(gate -> new Outcome(0, 0.0))).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("format  ok in 0.0s");
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void theScriptRunsAsAProgram() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path script = Check.root().resolve("scripts").resolve("Check.java");
        Process process = new ProcessBuilder(java.toString(), script.toString(), "--help")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(2, TimeUnit.MINUTES)).isTrue();
        assertThat(process.exitValue()).isZero();
        assertThat(stdout).contains("run the bench checks");
    }
}
