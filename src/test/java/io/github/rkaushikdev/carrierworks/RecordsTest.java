package io.github.rkaushikdev.carrierworks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecordsTest {

    private static final Instant NOW = Instant.parse("2025-09-26T10:00:00Z");

    private record Note(Instant at, String text) {}

    private static List<String> texts(List<Note> notes) {
        return notes.stream().map(Note::text).toList();
    }

    @Test
    void tagsNormaliseHoweverTheyWereWritten() {
        assertThat(Records.boardKey("BX-0014")).isEqualTo("bx-14");
        assertThat(Records.boardKey(" bx-14 ")).isEqualTo("bx-14");
    }

    @Test
    void aTagThatIsNotOneIsRefused() {
        assertThatThrownBy(() -> Records.boardKey("board 14")).isInstanceOf(ValidationError.class);
    }

    @Test
    void aWindowKeepsWhatIsInsideIt() {
        List<Note> notes = List.of(
                new Note(NOW.minus(Duration.ofHours(5)), "old"),
                new Note(NOW.minus(Duration.ofMinutes(30)), "recent"),
                new Note(NOW, "now"));
        List<Note> inside = Records.inWindow(notes, NOW, Duration.ofHours(1), Note::at);
        assertThat(texts(inside)).containsExactly("recent", "now");
    }

    @Test
    void aWindowOfNoLengthIsABug() {
        assertThatThrownBy(() -> Records.inWindow(List.<Note>of(), NOW, Duration.ZERO, Note::at))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aTallyCountsAndDescribes() {
        Map<String, Integer> counts = Records.tally(List.of("available", "faulted", "available"));
        assertThat(counts).isEqualTo(Map.of("available", 2, "faulted", 1));
        assertThat(Records.describeTally(counts)).isEqualTo("2 available, 1 faulted");
    }

    @Test
    void anEmptyTallySaysWhatItWasToldTo() {
        assertThat(Records.describeTally(Map.of())).isEqualTo("nothing");
        assertThat(Records.describeTally(Map.of(), "nothing being watched")).isEqualTo("nothing being watched");
    }

    @Test
    void entriesAreKeptPerBoardInOrder() {
        PerBoard<Note> held = new PerBoard<>();
        held.append("bx-14", new Note(NOW, "first"));
        held.append("bx-14", new Note(NOW, "second"));
        held.append("BX-0015", new Note(NOW, "other"));
        assertThat(texts(held.items("bx-14"))).containsExactly("first", "second");
        assertThat(held.boards()).containsExactly("bx-14", "bx-15");
        assertThat(held.size()).isEqualTo(3);
    }

    @Test
    void theLatestEntryComesBack() {
        PerBoard<Note> held = new PerBoard<>();
        assertThat(held.latest("bx-14")).isEmpty();
        held.append("bx-14", new Note(NOW, "first"));
        held.append("bx-14", new Note(NOW, "second"));
        assertThat(held.latest("bx-14").orElseThrow().text()).isEqualTo("second");
    }

    @Test
    void everythingComesBackBoardByBoard() {
        PerBoard<Note> held = new PerBoard<>();
        held.append("bx-15", new Note(NOW, "b"));
        held.append("bx-14", new Note(NOW, "a"));
        assertThat(texts(held.all())).containsExactly("a", "b");
    }

    @Test
    void aCapDropsTheOldestEntries() {
        PerBoard<Note> held = new PerBoard<>(2);
        for (int index = 0; index < 5; index++) {
            held.append("bx-14", new Note(NOW, String.valueOf(index)));
        }
        assertThat(texts(held.items("bx-14"))).containsExactly("3", "4");
    }

    @Test
    void aCapOfOneIsRefused() {
        assertThatThrownBy(() -> new PerBoard<Note>(1)).isInstanceOf(ValidationError.class);
    }

    @Test
    void droppingABoardSaysHowManyWent() {
        PerBoard<Note> held = new PerBoard<>();
        held.append("bx-14", new Note(NOW, "a"));
        held.append("bx-14", new Note(NOW, "b"));
        assertThat(held.drop("bx-14")).isEqualTo(2);
        assertThat(held.boards()).isEmpty();
        assertThat(held.drop("bx-14")).isZero();
    }

    @Test
    void replacingABoardSEntries() {
        PerBoard<Note> held = new PerBoard<>();
        held.append("bx-14", new Note(NOW, "a"));
        held.replace("bx-14", List.of(new Note(NOW, "b")));
        assertThat(texts(held.items("bx-14"))).containsExactly("b");
        held.replace("bx-14", List.of());
        assertThat(held.contains("bx-14")).isFalse();
    }

    @Test
    void pruningKeepsWhatThePredicateWants() {
        PerBoard<Note> held = new PerBoard<>();
        held.append("bx-14", new Note(NOW.minus(Duration.ofHours(2)), "old"));
        held.append("bx-14", new Note(NOW, "new"));
        held.append("bx-15", new Note(NOW.minus(Duration.ofHours(3)), "old"));
        int dropped = held.prune(note -> !note.at().isBefore(NOW.minus(Duration.ofHours(1))));
        assertThat(dropped).isEqualTo(2);
        assertThat(held.boards()).containsExactly("bx-14");
    }

    @Test
    void membershipReadsTheTag() {
        PerBoard<Note> held = new PerBoard<>();
        held.append("bx-14", new Note(NOW, "a"));
        assertThat(held.contains("BX-0014")).isTrue();
        assertThat(held.contains("bx-15")).isFalse();
        assertThat(held.contains(14)).isFalse();
    }
}
