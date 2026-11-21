package io.github.rkaushikdev.carrierworks.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.carrierworks.PduChannel;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import io.github.rkaushikdev.carrierworks.power.FakePdu;
import io.github.rkaushikdev.carrierworks.services.Inventory;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliPowerTest {

    private static final PduChannel OUTLET = new PduChannel("pdu-a", 7);

    @TempDir
    Path tmp;

    private BenchDatabase database;
    private Shell shell;

    @BeforeEach
    void url() {
        String address = Shell.url(tmp);
        database = Shell.migrated(address);
        database.run(unit -> {
            Inventory.addBoard(
                    unit, BoardSetup.kintex("bx-14").build(), Times.now().minus(Duration.ofDays(1)));
            Inventory.addBoard(
                    unit,
                    BoardSetup.artix("bx-15").noOutlet().build(),
                    Times.now().minus(Duration.ofDays(1)));
        });
        shell = new Shell(address);
    }

    @AfterEach
    void close() {
        PowerCommands.forgetPdus();
        database.close();
    }

    /** A distribution unit registered the way a site would register one. */
    private static FakePdu rack() {
        FakePdu unit = new FakePdu().withOutletOn(OUTLET).withCurrent(OUTLET, 1450);
        PowerCommands.registerPdu("pdu-a", unit);
        return unit;
    }

    private Shell.Result take(String holder) {
        return shell.run("lease", "take", "--board", "bx-14", "--holder", holder, "--purpose", "capture");
    }

    private static List<String> actions(JsonNode plan) {
        return Shell.column(plan, "action");
    }

    @Test
    void theTreeComesOutInDependencyOrder() {
        List<String> names =
                Shell.column(shell.run("--json", "power", "tree", "bx-14").json(), "rail");
        assertThat(names.getFirst()).isEqualTo("vccint");
        assertThat(names.indexOf("vccaux")).isGreaterThan(names.indexOf("vccint"));
    }

    @Test
    void everyRailSaysWhatItWaitsFor() {
        JsonNode body = shell.run("--json", "power", "tree", "bx-14").json();
        Map<String, String> after = new LinkedHashMap<>();
        body.forEach(entry ->
                after.put(entry.get("rail").asText(), entry.get("after").asText()));
        assertThat(after.get("vccint")).isEqualTo("-");
        assertThat(after.get("vccaux")).contains("vccint");
    }

    @Test
    void theUpPlanEnablesSettlesAndChecks() {
        List<String> actions =
                actions(shell.run("--json", "power", "plan", "bx-14").json());
        assertThat(actions.getFirst()).isEqualTo("enable");
        assertThat(actions).contains("check");
    }

    @Test
    void theDownPlanReversesTheOrder() {
        JsonNode up = shell.run("--json", "power", "plan", "bx-14").json();
        JsonNode down = shell.run("--json", "power", "plan", "bx-14", "--down").json();
        String firstUp = StreamSupport.stream(up.spliterator(), false)
                .filter(entry -> entry.get("action").asText().equals("enable"))
                .findFirst()
                .orElseThrow()
                .get("rail")
                .asText();
        List<String> disabled = StreamSupport.stream(down.spliterator(), false)
                .filter(entry -> entry.get("action").asText().equals("disable"))
                .map(entry -> entry.get("rail").asText())
                .toList();
        assertThat(disabled.getLast()).isEqualTo(firstUp);
    }

    @Test
    void aPlanInWordsSaysHowLongItTakes() {
        Shell.Result result = shell.run("power", "plan", "bx-14");
        assertThat(result.output()).contains("steps");
        assertThat(result.output()).contains("ms");
    }

    @Test
    void aBoardInMaintenanceBlocksThePlan() {
        shell.run("boards", "state", "bx-14", "maintenance", "--reason", "recapping the fmc");
        Shell.Result result = shell.run("power", "plan", "bx-14");
        assertThat(result.output()).contains("blocked");
        assertThat(result.output()).contains("maintenance");
    }

    @Test
    void theSimulatedOutletReadsAsOn() {
        JsonNode body = shell.run("--json", "power", "show", "bx-14").json();
        assertThat(body.get("on").isBoolean()).isTrue();
        assertThat(body.get("on").booleanValue()).isTrue();
        assertThat(body.get("outlet").asText()).isEqualTo("pdu-a/outlet-07");
        assertThat(body.get("blocked_by").isArray()).isTrue();
        assertThat(body.get("blocked_by")).isEmpty();
    }

    @Test
    void aBoardOnNoOutletCannotBeSwitched() {
        Shell.Result result = shell.run("power", "show", "bx-15");
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("not on a switched outlet");
    }

    @Test
    void switchingOffTheSimulatorChangesNothingReal() {
        JsonNode body = shell.run("--json", "power", "off", "bx-14").json();
        assertThat(body.get("changed").booleanValue()).isTrue();
        assertThat(shell.run("--json", "power", "show", "bx-14")
                        .json()
                        .get("on")
                        .booleanValue())
                .isTrue();
    }

    @Test
    void aLiveSwitchReachesTheRegisteredUnit() {
        FakePdu rack = rack();
        JsonNode body = shell.run("--json", "power", "off", "bx-14", "--live").json();
        assertThat(body.get("changed").booleanValue()).isTrue();
        assertThat(rack.isOn(OUTLET)).isFalse();
        assertThat(rack.commands()).containsExactly("off pdu-a/outlet-07");
    }

    @Test
    void switchingOffTwiceIsQuietTheSecondTime() {
        rack();
        shell.run("power", "off", "bx-14", "--live");
        Shell.Result result = shell.run("power", "off", "bx-14", "--live");
        assertThat(result.output()).contains("was already off");
    }

    @Test
    void aLiveSwitchWithNoRegisteredUnitIsAnError() {
        Shell.Result result = shell.run("power", "off", "bx-14", "--live");
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("no driver");
    }

    @Test
    void somebodyElsesLeaseBlocksTheSwitch() {
        FakePdu rack = rack();
        take("team-a");
        Shell.Result result = shell.run("power", "cycle", "bx-14", "--live", "--holder", "team-b");
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("interlocked");
        assertThat(rack.commands()).isEmpty();
    }

    @Test
    void theHolderOfTheLeaseCanCycleIt() {
        FakePdu rack = rack();
        take("team-a");
        JsonNode body = shell.run("--json", "power", "cycle", "bx-14", "--live", "--holder", "team-a")
                .json();
        assertThat(body.get("off_ms").numberValue()).isEqualTo(250);
        assertThat(rack.commands()).containsExactly("off pdu-a/outlet-07", "on pdu-a/outlet-07");
    }

    @Test
    void aCycleCanBeForcedPastALease() {
        FakePdu rack = rack();
        take("team-a");
        JsonNode body = shell.run("--json", "power", "cycle", "bx-14", "--live", "--holder", "team-b", "--force")
                .json();
        assertThat(body.get("blocked_by")).isNotEmpty();
        assertThat(rack.commands()).containsExactly("off pdu-a/outlet-07", "on pdu-a/outlet-07");
    }

    @Test
    void aShortOffTimeIsStretchedToTheFloor() {
        rack();
        JsonNode body = shell.run("--json", "power", "cycle", "bx-14", "--live", "--off-ms", "10")
                .json();
        assertThat(body.get("off_ms").numberValue()).isEqualTo(250);
    }

    @Test
    void aLongerOffTimeIsKept() {
        rack();
        JsonNode body = shell.run("--json", "power", "cycle", "bx-14", "--live", "--off-ms", "900")
                .json();
        assertThat(body.get("off_ms").numberValue()).isEqualTo(900);
    }

    @Test
    void anUnreachableUnitIsAPowerFault() {
        rack().withUnreachable("pdu-a");
        Shell.Result result = shell.run("power", "show", "bx-14", "--live");
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("did not answer");
    }

    @Test
    void theOutletReportsWhatItIsDrawing() {
        rack();
        JsonNode body = shell.run("--json", "power", "show", "bx-14", "--live").json();
        assertThat(body.get("current_ma").numberValue()).isEqualTo(1450);
        assertThat(body.get("pdu").asText()).isEqualTo("fake-pdu");
    }

    @Test
    void anOutletThatIsOffDrawsNothing() {
        rack().switchOutlet(OUTLET, false);
        JsonNode body = shell.run("--json", "power", "show", "bx-14", "--live").json();
        assertThat(body.get("on").isBoolean()).isTrue();
        assertThat(body.get("on").booleanValue()).isFalse();
        assertThat(body.get("current_ma").numberValue()).isEqualTo(0);
    }
}
