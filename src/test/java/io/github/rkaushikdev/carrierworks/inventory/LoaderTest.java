package io.github.rkaushikdev.carrierworks.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.carrierworks.ConfigurationError;
import io.github.rkaushikdev.carrierworks.util.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoaderTest {

    private static final Instant WHEN = Instant.parse("2025-07-18T08:00:00Z");

    private static final Map<String, Object> BX_14 = object(
            "asset", "bx-14",
            "model", "rx-carrier-b",
            "revision", "c",
            "part", "XC7K325T-2FFG900I",
            "flash", "n25q128a13",
            "location", "rack-2/shelf-04/slot-3",
            "slots",
                    List.of(
                            object("name", "fmc0", "connector", "fmc-hpc", "fitted", "fmc-adc-4ch-14b"),
                            object("name", "fmc1", "connector", "fmc-lpc")),
            "adapter", object("serial", "210308AB1234", "model", "jtag-hs2", "port", "usb-3"),
            "pdu", "pdu-a/outlet-07",
            "notes", "the one with the short ribbon");

    private static final Map<String, Object> BX_15 = object(
            "asset", "bx-15",
            "model", "tx-carrier-a",
            "part", "XC7A100T-2FGG484I",
            "flash", "w25q64jv",
            "location", "rack-4/shelf-01/slot-1",
            "state", "maintenance",
            "reason", "waiting on a new header");

    private static final Map<String, Object> BENCH = object("site", "lab-a", "boards", List.of(BX_14, BX_15));

    private static Map<String, Object> object(Object... pairs) {
        Map<String, Object> built = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            built.put((String) pairs[index], pairs[index + 1]);
        }
        return Collections.unmodifiableMap(built);
    }

    private static Map<String, Object> with(Map<String, Object> entry, String key, Object value) {
        Map<String, Object> changed = new LinkedHashMap<>(entry);
        changed.put(key, value);
        return changed;
    }

    private static String text() {
        return Json.write(BENCH);
    }

    private static Map<?, ?> firstBoard(String document) {
        Map<?, ?> parsed = (Map<?, ?>) Json.parse(document);
        return (Map<?, ?>) ((List<?>) parsed.get("boards")).getFirst();
    }

    @Test
    void aBenchFileBecomesABook() {
        BoardBook book = BenchFile.read(text(), WHEN);
        assertThat(book.size()).isEqualTo(2);
        assertThat(book.board("bx-14").model()).isEqualTo("rx-carrier-b");
        assertThat(book.board("bx-14").revision()).isEqualTo("c");
        assertThat(book.board("bx-14").notes()).isEqualTo("the one with the short ribbon");
    }

    @Test
    void slotsAndCardsComeThrough() {
        Board board = BenchFile.read(text(), WHEN).board("bx-14");
        assertThat(board.slot("fmc0").fitted()).contains("fmc-adc-4ch-14b");
        assertThat(board.slot("fmc1").isEmpty()).isTrue();
    }

    @Test
    void theCableAndTheOutletComeThrough() {
        Board board = BenchFile.read(text(), WHEN).board("bx-14");
        assertThat(board.adapter()).isPresent();
        assertThat(board.adapter().orElseThrow().serial()).isEqualTo("210308AB1234");
        assertThat(board.adapter().orElseThrow().port()).isEqualTo("usb-3");
        assertThat(board.pdu().map(Object::toString)).contains("pdu-a/outlet-07");
    }

    @Test
    void aBoardCanArriveOutOfServiceWithItsReason() {
        BoardState state = BenchFile.read(text(), WHEN).state("bx-15");
        assertThat(state.state()).isEqualTo(BoardStatus.MAINTENANCE);
        assertThat(state.reason()).isEqualTo("waiting on a new header");
    }

    @Test
    void defaultsFillInForEverythingOptional() {
        Board board = BenchFile.read(text(), WHEN).board("bx-15");
        assertThat(board.revision()).isEqualTo("a");
        assertThat(board.vadjMillivolts()).isEqualTo(1800);
        assertThat(board.maxTckHz()).isEqualTo(30_000_000);
        assertThat(board.adapter()).isEmpty();
        assertThat(board.pdu()).isEmpty();
    }

    @Test
    void aFileThatIsNotJsonIsRefused() {
        assertThatThrownBy(() -> BenchFile.read("{not json")).isInstanceOf(ConfigurationError.class);
    }

    @Test
    void aFileWithoutBoardsIsRefused() {
        assertThatThrownBy(() -> BenchFile.read(Json.write(Map.of("site", "lab-a"))))
                .isInstanceOf(ConfigurationError.class);
        assertThatThrownBy(() -> BenchFile.read(Json.write(List.of(1, 2, 3)))).isInstanceOf(ConfigurationError.class);
    }

    @Test
    void aBoardEntryThatIsNotAnObjectIsRefused() {
        assertThatThrownBy(() -> BenchFile.read(Json.write(Map.of("boards", List.of("bx-14")))))
                .isInstanceOf(ConfigurationError.class);
    }

    @Test
    void aBoardMissingAFieldNamesTheBoardAndTheField() {
        Map<String, Object> document = object("boards", List.of(object("asset", "bx-14", "model", "rx-carrier-b")));
        ConfigurationError caught =
                catchThrowableOfType(ConfigurationError.class, () -> BenchFile.read(Json.write(document)));
        assertThat(caught.details()).containsEntry("board", "bx-14");
        assertThat(caught.details().get("missing")).asString().contains("part");
    }

    @Test
    void aBoardWithABadValueNamesTheBoard() {
        Map<String, Object> document = object("boards", List.of(with(BX_14, "part", "XC7A100T")));
        ConfigurationError caught =
                catchThrowableOfType(ConfigurationError.class, () -> BenchFile.read(Json.write(document)));
        assertThat(caught.details()).containsEntry("board", "bx-14");
    }

    @Test
    void twoBoardsInOneSlotAreRefusedByTheBook() {
        Map<String, Object> clash = with(BX_15, "location", "rack-2/shelf-04/slot-3");
        Map<String, Object> document = object("boards", List.of(BX_14, clash));
        assertThatThrownBy(() -> BenchFile.read(Json.write(document))).isInstanceOf(ConfigurationError.class);
    }

    @Test
    void aFileIsLoadedFromDisk(@TempDir Path tmp) throws IOException {
        Path path = tmp.resolve("bench.json");
        Files.writeString(path, text(), StandardCharsets.UTF_8);
        assertThat(BenchFile.load(path, WHEN).size()).isEqualTo(2);
    }

    @Test
    void aMissingFileIsRefused(@TempDir Path tmp) {
        assertThatThrownBy(() -> BenchFile.load(tmp.resolve("nothing.json"))).isInstanceOf(ConfigurationError.class);
    }

    @Test
    void aBookRoundTripsThroughTheDocument() {
        BoardBook book = BenchFile.read(text(), WHEN);
        BoardBook again = BenchFile.read(BenchFile.dump(book), WHEN);
        assertThat(again.tags()).isEqualTo(book.tags());
        assertThat(again.board("bx-14").describe())
                .isEqualTo(book.board("bx-14").describe());
        assertThat(again.state("bx-15").state()).isEqualTo(BoardStatus.MAINTENANCE);
    }

    @Test
    void theDumpLeavesOutWhatIsNotThere() {
        BoardBook book = new BoardBook(WHEN);
        book.add(BoardSetup.artix("bx-15").bare().slots().build());
        List<Object> keys = List.copyOf(firstBoard(BenchFile.dump(book)).keySet());
        assertThat(keys).doesNotContain("adapter", "pdu", "slots", "notes");
    }

    @Test
    void oneBoardRoundTripsOnItsOwn() {
        Board board = BoardSetup.kintex();
        assertThat(BoardJson.fromJson(BoardJson.toJson(board)).describe()).isEqualTo(board.describe());
    }

    @Test
    void anEmptySlotIsWrittenWithoutAFittedKey() {
        Map<String, Object> entry = BoardJson.toJson(BoardSetup.kintex());
        assertThat(((List<?>) entry.get("slots")).get(1)).isEqualTo(Map.of("name", "fmc1", "connector", "fmc-lpc"));
    }
}
