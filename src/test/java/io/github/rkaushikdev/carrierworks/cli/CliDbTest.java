package io.github.rkaushikdev.carrierworks.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code fpga db upgrade}: what a fresh site runs first, and what an upgraded one runs again. */
class CliDbTest {

    @TempDir
    Path tmp;

    @Test
    void anEmptyDatabaseIsRefusedUntilItsSchemaIsBuilt() {
        Shell shell = new Shell(Shell.url(tmp, "fresh"));
        Shell.Result before = shell.run("boards", "list");
        assertThat(before.exitCode()).isEqualTo(1);
        assertThat(before.output()).contains("no schema yet, run fpga db upgrade");

        Shell.Result upgraded = shell.run("db", "upgrade");
        assertThat(upgraded.exitCode()).isZero();
        assertThat(upgraded.output()).startsWith("schema built at version ").contains(" migrations");

        Shell.Result after = shell.run("boards", "list");
        assertThat(after.exitCode()).isZero();
        assertThat(after.output()).isEqualTo("nothing to show\n");
    }

    @Test
    void upgradingTwiceChangesNothingTheSecondTime() {
        Shell shell = new Shell(Shell.url(tmp));
        JsonNode first = shell.run("--json", "db", "upgrade").json();
        JsonNode second = shell.run("--json", "db", "upgrade").json();

        assertThat(first.get("from").isNull()).isTrue();
        assertThat(first.get("applied").asInt()).isPositive();
        assertThat(second.get("from").asText()).isEqualTo(first.get("to").asText());
        assertThat(second.get("to").asText()).isEqualTo(first.get("to").asText());
        assertThat(second.get("applied").asInt()).isZero();
        assertThat(shell.run("db", "upgrade").output())
                .isEqualTo("the schema is already at version " + first.get("to").asText() + "\n");
    }

    @Test
    void aDatabaseThatCannotBeReachedIsOneLineAndExitOne() {
        Shell shell = new Shell("jdbc:h2:tcp://127.0.0.1:1/nowhere");
        Shell.Result result = shell.run("db", "upgrade");
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).startsWith("Error: that database could not be used");
        assertThat(result.output().lines()).hasSize(1);
    }
}
