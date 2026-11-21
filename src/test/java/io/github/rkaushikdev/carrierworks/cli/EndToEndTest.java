package io.github.rkaushikdev.carrierworks.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.carrierworks.bitstream.Images;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One day on the bench, from racking a board to signing off a release.
 *
 * <p>Every other test holds one thing still and pushes on another. This one does not: it runs the
 * whole path the lab actually walks, through the command line, in the order it happens, against one
 * database on disk. It exists because three defects have got through the unit tests by living in
 * the seams: a lease that the job gate could not see, a report that was stored against the wrong
 * digest, and a bundle that named a file it had not written.
 *
 * <p>It is deliberately one long test rather than fifteen small ones. The value is in the order, and
 * a fixture that set up step nine would be asserting that step eight worked rather than running it.
 */
class EndToEndTest {

    private static final String BENCH =
            """
            {"site": "lab-a", "boards": [
              {"asset": "bx-31", "model": "rx-carrier-b", "part": "XC7K325T-2FFG900I",
               "flash": "n25q128a13", "location": "rack-7/shelf-01/slot-1",
               "adapter": {"serial": "210308FA0001", "model": "jtag-hs2"}, "pdu": "pdu-e/outlet-01",
               "slots": [{"name": "fmc0", "connector": "fmc-hpc", "fitted": "fmc-adc-4ch-14b"}]},
              {"asset": "bx-32", "model": "tx-carrier-a", "part": "XC7A100T-2FGG484I",
               "flash": "w25q64jv", "location": "rack-7/shelf-01/slot-2",
               "adapter": {"serial": "210308FA0002", "model": "jtag-hs3"}, "pdu": "pdu-e/outlet-02"}
            ]}
            """;

    @TempDir
    Path root;

    private BenchDatabase database;
    private Shell shell;

    /** A bench with nothing in it and somewhere to put files. */
    @BeforeEach
    void lab() throws IOException {
        String url = Shell.url(root);
        database = new BenchDatabase(Shell.settings(url).toBuilder()
                .artifactRoot(root.resolve("artifacts"))
                .journalRoot(root.resolve("journal"))
                .wearLedger(root.resolve("wear.json"))
                .build());
        database.createSchema();
        shell = new Shell(url)
                .env("FPGA_ARTIFACT_ROOT", root.resolve("artifacts").toString())
                .env("FPGA_JOURNAL_ROOT", root.resolve("journal").toString())
                .env("FPGA_WEAR_LEDGER", root.resolve("wear.json").toString());
        Files.writeString(root.resolve("bench.json"), BENCH, StandardCharsets.UTF_8);
        Files.write(root.resolve("radar.bit"), Images.bitfile(64));
        Files.writeString(root.resolve("timing.rpt"), Images.TIMING, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("usage.rpt"), Images.UTILIZATION, StandardCharsets.UTF_8);
    }

    @AfterEach
    void close() {
        database.close();
    }

    private String file(String name) {
        return root.resolve(name).toString();
    }

    /** Run a command that has to work. */
    private Shell.Result run(String... args) {
        Shell.Result result = shell.run(args);
        assertThat(result.exitCode())
                .as("%s exited %d: %s", List.of(args), result.exitCode(), result.output())
                .isZero();
        return result;
    }

    /** Run a command that has to work, and read what it printed as JSON. */
    private JsonNode ask(String... args) {
        List<String> line = new ArrayList<>(List.of("--json"));
        line.addAll(List.of(args));
        return run(line.toArray(String[]::new)).json();
    }

    /** Run something the bench should turn down, and hand back what it said. */
    private String refuse(String... args) {
        Shell.Result result = shell.run(args);
        assertThat(result.exitCode())
                .as("%s was allowed: %s", List.of(args), result.output())
                .isNotZero();
        return result.output();
    }

    private String upload() {
        return ask(
                        "--json",
                        "image",
                        "upload",
                        file("radar.bit"),
                        "--channel",
                        "qual",
                        "--timing",
                        file("timing.rpt"),
                        "--utilization",
                        file("usage.rpt"))
                .get("digest")
                .asText();
    }

    @Test
    void aDayOnTheBench() {
        // The boards arrive and are racked from the file the person wrote.
        JsonNode added = ask("boards", "import", file("bench.json"));
        assertThat(Shell.texts(added.get("added"))).containsExactly("bx-31", "bx-32");
        assertThat(Shell.column(ask("boards", "list"), "asset")).containsOnly("bx-31", "bx-32");

        // Nobody has calibrated the one with a converter on it, so it is overdue.
        assertThat(Shell.column(ask("upkeep", "due"), "asset")).containsExactly("bx-31");
        run("upkeep", "done", "bx-31", "--by", "sam", "--kind", "calibration");
        JsonNode due = ask("upkeep", "due");
        assertThat(due.isArray()).isTrue();
        assertThat(due).isEmpty();

        // The chain answers with what the inventory says is on the header.
        JsonNode scanned = ask("chain", "scan", "bx-31");
        assertThat(scanned.get("ok").isBoolean()).isTrue();
        assertThat(scanned.get("ok").booleanValue()).isTrue();
        assertThat(scanned.get("found").asText()).contains("xc7k325t");

        // The rack controller starts reporting temperatures.
        run("thermal", "record", "bx-31", "58.5C");
        run("thermal", "record", "bx-32", "51.0C");
        Map<String, String> states = new TreeMap<>();
        ask("thermal", "status")
                .forEach(entry -> states.put(
                        entry.get("asset").asText(), entry.get("state").asText()));
        assertThat(states).isEqualTo(Map.of("bx-31", "normal", "bx-32", "normal"));

        // The build system uploads an image and the two reports together.
        JsonNode uploaded = ask(
                "--json",
                "image",
                "upload",
                file("radar.bit"),
                "--channel",
                "qual",
                "--timing",
                file("timing.rpt"),
                "--utilization",
                file("usage.rpt"));
        String digest = uploaded.get("digest").asText();
        assertThat(uploaded.get("design").asText()).isEqualTo("radar_frontend");
        assertThat(uploaded.get("timing_closed").isBoolean()).isTrue();
        assertThat(uploaded.get("timing_closed").booleanValue()).isTrue();

        // Somebody asks for a board by what it has to do, and the pass hands one over.
        run("queue", "submit", "--holder", "team-a", "--purpose", "capture run", "--needs", "adc-4ch");
        JsonNode dispatched = ask("queue", "dispatch");
        assertThat(Shell.column(dispatched.get("assigned"), "board")).containsExactly("bx-31");
        assertThat(ask("boards", "show", "bx-31").get("held_by").asText()).isEqualTo("team-a");

        // The job runs against the simulator and the board comes back leased.
        run("job", "submit", "--board", "bx-31", "--image", digest, "--by", "team-a");
        JsonNode ran = ask("job", "run");
        assertThat(ran.get("state").asText()).isEqualTo("passed");
        assertThat(ask("boards", "show", "bx-31").get("state").asText()).isEqualTo("leased");

        // A flash write costs what the plan said it would.
        JsonNode plan = ask("flash", "plan", "bx-31", "--image", digest);
        assertThat(Shell.column(plan, "kind")).contains("erase", "program", "verify");

        // The lease is nearly up, and the holder gets a line in the trail about it.
        run("lease", "renew", "lease-00001", "--minutes", "1");
        JsonNode warned = ask("lease", "expiring", "--remind");
        assertThat(Shell.column(warned, "lease")).containsExactly("lease-00001");
        assertThat(Shell.column(ask("audit", "--action", "expiring"), "action")).containsExactly("expiring");

        // Everything about the image goes into a bundle that checks out.
        JsonNode bundled = ask("image", "bundle", digest, "--into", file("release"), "--approved-by", "kim");
        assertThat(bundled.get("jobs").numberValue()).isEqualTo(1);
        assertThat(bundled.get("reports").numberValue()).isEqualTo(1);
        assertThat(run("image", "check-bundle", bundled.get("root").asText()).output())
                .contains("matches its manifest");

        // Golden is refused, and the refusal names what is still missing. One passing load and no
        // record of where the image came from is not enough to put an image in the half of the
        // flash a board boots from.
        String refused = refuse("image", "promote", digest, "golden", "--approve", "kim");
        assertThat(refused).contains("nothing recorded where this image came from");
        assertThat(refused).contains("clean loads");

        // The board goes back, and the bench reads as quiet again.
        run("lease", "release", "lease-00001", "--reason", "capture finished");
        JsonNode state = ask("status");
        assertThat(state.get("available").numberValue()).isEqualTo(2);
        assertThat(state.get("leases_held").numberValue()).isEqualTo(0);
        assertThat(state.get("calibration_overdue").isArray()).isTrue();
        assertThat(state.get("calibration_overdue")).isEmpty();

        // And the day shows up in the usage report.
        JsonNode summary = ask("usage", "summary", "--days", "1");
        assertThat(summary.get("leases").numberValue()).isEqualTo(1);
        assertThat(summary.get("busiest").asText()).isEqualTo("bx-31");
    }

    @Test
    void aWorkerPicksUpWhatTheDayLeftBehind() {
        run("boards", "import", file("bench.json"));
        String digest = upload();

        // A request waiting, and a job queued behind the lease it will get.
        run("queue", "submit", "--holder", "team-b", "--purpose", "overnight", "--needs", "device=xc7k325t");
        JsonNode worked = ask("worker", "run");
        assertThat(Shell.texts(worked.get("assigned"))).containsExactly("req-0001 -> bx-31");

        run("job", "submit", "--board", "bx-31", "--image", digest, "--by", "team-b");
        JsonNode second = ask("worker", "run");
        assertThat(Shell.column(second.get("ran"), "state")).containsExactly("passed");
        assertThat(ask("job", "list").get(0).get("state").asText()).isEqualTo("passed");
    }
}
