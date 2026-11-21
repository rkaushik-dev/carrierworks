package io.github.rkaushikdev.carrierworks.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.carrierworks.ConfigurationError;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.catalog.CardSpec;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import io.github.rkaushikdev.carrierworks.services.BenchFile;
import io.github.rkaushikdev.carrierworks.services.ImportOutcome;
import io.github.rkaushikdev.carrierworks.services.Inventory;
import io.github.rkaushikdev.carrierworks.util.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchFileTest {

    private static final Instant WHEN = Instant.parse("2026-06-01T09:00:00Z");

    private static final String BENCH =
            """
            {"site": "lab-a", "boards": [
              {"asset": "bx-20", "model": "rx-carrier-b", "part": "XC7K325T-2FFG900I",
               "flash": "n25q128a13", "location": "rack-3/shelf-02/slot-1",
               "adapter": {"serial": "210308EE0001", "model": "jtag-hs2"}, "pdu": "pdu-c/outlet-03",
               "slots": [{"name": "fmc0", "connector": "fmc-hpc", "fitted": "fmc-adc-4ch-14b"}]},
              {"asset": "bx-21", "model": "tx-carrier-a", "part": "XC7A100T-2FGG484I",
               "flash": "w25q64jv", "location": "rack-3/shelf-02/slot-2"}
            ]}
            """;

    @TempDir
    Path tmp;

    private BenchDatabase database;
    private Shell shell;

    @BeforeEach
    void database() {
        String url = Shell.url(tmp);
        database =
                new BenchDatabase(Shell.settings(url).toBuilder().site("lab-a").build());
        database.createSchema();
        shell = new Shell(url);
    }

    @AfterEach
    void close() {
        database.close();
    }

    private ImportOutcome load() {
        return load(BENCH, false);
    }

    private ImportOutcome load(String text, boolean replace) {
        return database.apply(unit -> BenchFile.importBench(unit, text, WHEN, replace));
    }

    private Path written() throws IOException {
        Path path = tmp.resolve("bench.json");
        Files.writeString(path, BENCH, StandardCharsets.UTF_8);
        return path;
    }

    @Test
    void aFilePutsItsBoardsOnTheBench() {
        ImportOutcome outcome = load();
        assertThat(outcome.added()).containsExactly("bx-20", "bx-21");
        database.run(
                unit -> assertThat(unit.boards().all().stream().map(Board::tag)).containsOnly("bx-20", "bx-21"));
    }

    @Test
    void theCardsComeAcross() {
        load();
        Board board = database.apply(unit -> unit.boards().get("bx-20"));
        assertThat(board.fittedCards().stream().map(CardSpec::name)).containsExactly("fmc-adc-4ch-14b");
    }

    @Test
    void runningTheSameFileTwiceChangesNothing() {
        load();
        ImportOutcome outcome = load();
        assertThat(outcome.added()).isEmpty();
        assertThat(outcome.skipped()).containsExactly("bx-20", "bx-21");
    }

    @Test
    void replacingIsAskedForExplicitly() {
        load();
        String moved = BENCH.replace("rack-3/shelf-02/slot-1", "rack-4/shelf-01/slot-1");
        ImportOutcome outcome = load(moved, true);
        assertThat(outcome.replaced()).containsExactly("bx-20", "bx-21");
        database.run(unit ->
                assertThat(unit.boards().get("bx-20").location().toString()).isEqualTo("rack-4/shelf-01/slot-1"));
    }

    @Test
    void aFileThatIsNotJsonIsRefused() {
        assertThatThrownBy(() -> database.run(unit -> BenchFile.importBench(unit, "{not json", WHEN)))
                .isInstanceOf(ConfigurationError.class);
    }

    @Test
    void aBoardMissingAFieldIsRefused() {
        String broken = "{\"site\": \"lab-a\", \"boards\": [{\"asset\": \"bx-22\"}]}";
        assertThatThrownBy(() -> load(broken, false)).isInstanceOf(ConfigurationError.class);
    }

    @Test
    void theOutcomeReadsAsOneLine() {
        assertThat(load().describe()).contains("added bx-20, bx-21");
    }

    @Test
    void anEmptyFileSaysSo() {
        assertThat(load("{\"site\": \"lab-a\", \"boards\": []}", false).describe())
                .isEqualTo("nothing in that file");
    }

    @Test
    void theExportCarriesEveryBoard() {
        load();
        Map<String, Object> payload = database.apply(BenchFile::exportBench);
        assertThat(assets(payload)).containsExactly("bx-20", "bx-21");
    }

    @Test
    void theExportCarriesTheState() {
        load();
        Map<String, Object> payload = database.apply(unit -> {
            Inventory.moveState(unit, "bx-20", BoardStatus.MAINTENANCE, WHEN, "fan", "sam");
            return BenchFile.exportBench(unit);
        });
        Map<Object, Object> states = boards(payload).stream()
                .collect(Collectors.toMap(entry -> entry.get("asset"), entry -> entry.get("state")));
        assertThat(states.get("bx-20")).isEqualTo("maintenance");
    }

    @Test
    void anExportReadsBackAsTheSameBench() {
        load();
        Map<String, Object> payload = database.apply(BenchFile::exportBench);
        try (BenchDatabase other = Shell.migrated(Shell.url(tmp, "other"))) {
            other.run(unit -> {
                ImportOutcome outcome =
                        BenchFile.importBench(unit, Json.writer().textFallback().write(payload), WHEN);
                assertThat(outcome.added()).containsExactly("bx-20", "bx-21");
                assertThat(unit.boards().get("bx-20").flash()).isEqualTo("n25q128a13");
            });
        }
    }

    @Test
    void readingAFileThatIsNotThereIsAnError() {
        assertThatThrownBy(() -> BenchFile.readFile(tmp.resolve("nothing.json")))
                .isInstanceOf(NotFound.class);
    }

    @Test
    void theCommandImportsAFile() throws IOException {
        JsonNode body =
                shell.run("--json", "boards", "import", written().toString()).json();
        assertThat(Shell.texts(body.get("added"))).containsExactly("bx-20", "bx-21");
    }

    @Test
    void theCommandLeavesKnownBoardsAlone() throws IOException {
        database.run(
                unit -> Inventory.addBoard(unit, BoardSetup.kintex("bx-20").build(), WHEN.minus(Duration.ofDays(1))));
        JsonNode body =
                shell.run("--json", "boards", "import", written().toString()).json();
        assertThat(Shell.texts(body.get("skipped"))).containsExactly("bx-20");
    }

    @Test
    void theCommandCanReplace() throws IOException {
        database.run(
                unit -> Inventory.addBoard(unit, BoardSetup.kintex("bx-20").build(), WHEN.minus(Duration.ofDays(1))));
        JsonNode body = shell.run("--json", "boards", "import", written().toString(), "--replace")
                .json();
        assertThat(Shell.texts(body.get("replaced"))).containsExactly("bx-20");
    }

    @Test
    void importingAFileThatIsNotThereIsAnError() {
        assertThat(shell.run("boards", "import", tmp.resolve("nothing.json").toString())
                        .exitCode())
                .isNotZero();
    }

    @Test
    void theCommandExportsToTheTerminal() throws IOException {
        shell.run("boards", "import", written().toString());
        JsonNode payload = shell.run("boards", "export").json();
        assertThat(payload.get("site").asText()).isEqualTo("lab-a");
        assertThat(payload.get("boards").size()).isEqualTo(2);
    }

    @Test
    void theCommandExportsToAFile() throws IOException {
        shell.run("boards", "import", written().toString());
        Path out = tmp.resolve("out.json");
        Shell.Result result = shell.run("boards", "export", "--into", out.toString());
        assertThat(result.output()).contains("2 boards written");
        Object document = Json.parse(Files.readString(out, StandardCharsets.UTF_8));
        assertThat(assets(document)).first().isEqualTo("bx-20");
    }

    private static List<Map<?, ?>> boards(Object document) {
        if (!(document instanceof Map<?, ?> bench) || !(bench.get("boards") instanceof List<?> boards)) {
            throw new AssertionError("not a bench file: " + document);
        }
        List<Map<?, ?>> found = new ArrayList<>();
        for (Object entry : boards) {
            if (!(entry instanceof Map<?, ?> board)) {
                throw new AssertionError("not a board: " + entry);
            }
            found.add(board);
        }
        return found;
    }

    private static List<Object> assets(Object document) {
        return boards(document).stream()
                .<Object>map(board -> board.get("asset"))
                .toList();
    }
}
