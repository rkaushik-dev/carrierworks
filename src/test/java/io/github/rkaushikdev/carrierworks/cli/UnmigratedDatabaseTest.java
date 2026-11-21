package io.github.rkaushikdev.carrierworks.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.ConfigurationError;
import io.github.rkaushikdev.carrierworks.api.Http;
import io.github.rkaushikdev.carrierworks.api.TestBench;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import io.github.rkaushikdev.carrierworks.persistence.Engine;
import io.github.rkaushikdev.carrierworks.services.Inventory;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A database file nobody has migrated, which is what every fresh site starts with. */
class UnmigratedDatabaseTest {

    private static final Instant WHEN = Instant.parse("2026-06-25T09:00:00Z");

    @TempDir
    static Path tmp;

    /** A database file with nothing in it, which is a site nobody migrated. */
    private static String empty;

    private static TestBench bench;

    @BeforeAll
    static void start() {
        empty = Shell.url(tmp, "nothing");
        bench = TestBench.over(new BenchDatabase(Shell.settings(empty)), BenchSettings.defaults(), WHEN);
    }

    @AfterAll
    static void stop() {
        bench.close();
    }

    private static List<Board> readBoards(String url) {
        try (BenchDatabase database = new BenchDatabase(Shell.settings(url))) {
            return database.apply(unit -> unit.boards().all());
        }
    }

    private static ConfigurationError refused() {
        return catchThrowableOfType(ConfigurationError.class, () -> readBoards(empty));
    }

    @Test
    void aQueryAgainstNoSchemaIsABenchError() {
        assertThat(refused().message()).contains("no schema yet");
    }

    @Test
    void theErrorSaysWhichSettingIsWrong() {
        assertThat(refused().details()).containsEntry("setting", "database_url");
    }

    /**
     * The error keeps the first line of what went wrong underneath. The mapping is checked against
     * the schema as the first unit of work opens, before any query is sent, so on a database with no
     * tables that line is the schema check's, naming the first table it found missing.
     */
    @Test
    void theErrorKeepsTheDriversOwnLine() {
        assertThat(String.valueOf(refused().details().get("detail")))
                .isEqualTo("Schema-validation: missing table [api_keys]");
    }

    @Test
    void theErrorDoesNotCarryTheUrl() {
        String details = refused().details().toString();
        assertThat(details).doesNotContain("jdbc:h2").doesNotContain(tmp.toString());
    }

    @Test
    void aMigratedDatabaseAnswersNormally(@TempDir Path elsewhere) {
        String ready = Shell.url(elsewhere);
        try (BenchDatabase database = Shell.migrated(ready)) {
            database.run(unit ->
                    Inventory.addBoard(unit, BoardSetup.kintex("bx-14").build(), WHEN.minus(Duration.ofDays(1))));
        }
        assertThat(readBoards(ready)).extracting(Board::tag).containsExactly("bx-14");
    }

    /**
     * What somebody at the terminal sees is the one line the command prints: standard error is
     * caught as well, so a log line or a stack trace written beside the command's own output fails
     * the test.
     */
    @Test
    void theCommandLinePrintsOneLineAndExitsOne() {
        PrintStream terminal = System.err;
        ByteArrayOutputStream logged = new ByteArrayOutputStream();
        Shell.Result result;
        System.setErr(new PrintStream(logged, true, StandardCharsets.UTF_8));
        try {
            result = new Shell(empty).run("boards", "list");
        } finally {
            System.setErr(terminal);
        }
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("no schema yet");
        assertThat(result.output().lines()).hasSize(1);
        assertThat(result.output()).doesNotContain("\tat ").doesNotContain("Exception");
        assertThat(logged.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void theApiAnswersFiveHundredRatherThanCrashing() {
        Http.Answer response = bench.http().get("/boards");
        assertThat(response.status()).isEqualTo(500);
        assertThat(response.json().get("error").get("code").asText()).isEqualTo("configuration");
    }

    @Test
    void readinessSaysItIsNotReady() {
        Http.Answer response = bench.http().get("/ready");
        assertThat(response.status()).isEqualTo(503);
        JsonNode body = response.json();
        assertThat(body.get("ready").asBoolean()).isFalse();
        assertThat(body.get("reason").asText()).contains("no schema yet");
    }

    @Test
    void aDatabaseThatCannotBeOpenedAtAllReadsDifferently() {
        ConfigurationError translated = Engine.unusableDatabase(new SQLException("unable to open database file"));
        assertThat(translated.message()).isEqualTo("that database could not be used");
        assertThat(String.valueOf(translated.details().get("detail"))).contains("unable to open");
    }

    @Test
    void theMissingTableWordingCoversPostgresToo() {
        ConfigurationError translated =
                Engine.unusableDatabase(new SQLException("ERROR: relation \"boards\" does not exist"));
        assertThat(translated.message()).contains("no schema yet");
    }
}
