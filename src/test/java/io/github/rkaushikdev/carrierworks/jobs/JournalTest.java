package io.github.rkaushikdev.carrierworks.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.ValidationError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JournalTest {

    private static final Instant NOW = Instant.parse("2025-10-16T09:00:00Z");

    @TempDir
    Path tmp;

    private JobJournal journal() {
        return new JobJournal(tmp.resolve("journal").resolve("jobs.jsonl"));
    }

    private static List<String> lines(JobJournal book) throws IOException {
        return Files.readAllLines(book.path(), StandardCharsets.UTF_8);
    }

    @Test
    void aLineRoundTrips() {
        JournalEntry entry = new JournalEntry(NOW, "job-00001", JournalEvent.STARTED, Map.of("board", "bx-14"));
        assertThat(entry.toLine())
                .isEqualTo("{\"at\": \"2025-10-16T09:00:00.000+00:00\", \"detail\": {\"board\": \"bx-14\"},"
                        + " \"event\": \"started\", \"job\": \"job-00001\"}");
        assertThat(JournalEntry.fromLine(entry.toLine())).isEqualTo(entry);
    }

    @Test
    void aLineWithoutDetailLeavesTheKeyOut() {
        JournalEntry entry = new JournalEntry(NOW, "job-00001", JournalEvent.CREATED);
        assertThat(entry.toLine()).doesNotContain("detail");
        assertThat(JournalEntry.fromLine(entry.toLine()).detail()).isEmpty();
    }

    @Test
    void anEventNobodyHasIsRefused() {
        assertThatThrownBy(() -> new JournalEntry(NOW, "job-00001", JournalEvent.parse("exploded")))
                .isInstanceOf(ValidationError.class);
    }

    /**
     * A journal time with no zone is refused. An entry keeps its moment as an {@link Instant}, which
     * is always UTC, so a zoneless time cannot be handed in, and a line on disk whose time names no
     * offset is refused when it is read back.
     */
    @Test
    void journalTimesAreInUtc() {
        assertThat(Arrays.stream(JournalEntry.class.getRecordComponents())
                        .filter(component -> component.getName().equals("at"))
                        .<Object>map(component -> component.getType()))
                .containsExactly(Instant.class);
        assertThatThrownBy(() -> JournalEntry.fromLine(
                        "{\"at\": \"2025-10-16T09:00:00.000\", \"event\": \"created\", \"job\": \"job-00001\"}"))
                .isInstanceOf(ValidationError.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "[]", "{\"job\": \"job-1\"}"})
    void aLineThatIsNotOneIsRefused(String line) {
        assertThatThrownBy(() -> JournalEntry.fromLine(line)).isInstanceOf(ValidationError.class);
    }

    @Test
    void writingAndReadingAJournal() {
        JobJournal book = journal();
        book.append("job-00001", JournalEvent.CREATED, NOW, Map.of("board", "bx-14"));
        book.append("job-00001", JournalEvent.STARTED, NOW.plusSeconds(1));
        assertThat(book.entries().stream().map(JournalEntry::event))
                .containsExactly(JournalEvent.CREATED, JournalEvent.STARTED);
    }

    @Test
    void aJournalThatDoesNotExistYetIsEmpty() {
        assertThat(journal().entries()).isEmpty();
        assertThat(journal().unfinished()).isEmpty();
    }

    @Test
    void theFileIsWrittenAsItGoes() throws IOException {
        JobJournal book = journal();
        book.append("job-00001", JournalEvent.CREATED, NOW);
        assertThat(lines(book)).hasSize(1);
        book.append("job-00001", JournalEvent.STARTED, NOW);
        assertThat(lines(book)).hasSize(2);
    }

    @Test
    void entriesComeBackPerJob() {
        JobJournal book = journal();
        book.append("job-00001", JournalEvent.CREATED, NOW);
        book.append("job-00002", JournalEvent.CREATED, NOW);
        book.append("job-00001", JournalEvent.STARTED, NOW);
        assertThat(book.forJob("job-00001")).hasSize(2);
        assertThat(book.lastEvent("job-00001")).map(JournalEntry::event).contains(JournalEvent.STARTED);
        assertThat(book.lastEvent("job-00009")).isEmpty();
    }

    @Test
    void aJobThatStartedAndNeverFinishedIsUnfinished() {
        JobJournal book = journal();
        book.append("job-00001", JournalEvent.CREATED, NOW);
        book.append("job-00001", JournalEvent.STARTED, NOW);
        assertThat(book.unfinished()).containsExactly("job-00001");
    }

    @Test
    void aJobThatFinishedIsNot() {
        JobJournal book = journal();
        book.append("job-00001", JournalEvent.STARTED, NOW);
        book.append("job-00001", JournalEvent.PASSED, NOW.plus(Duration.ofMinutes(1)));
        assertThat(book.unfinished()).isEmpty();
    }

    @Test
    void aJobThatFailedIsNotUnfinishedEither() {
        JobJournal book = journal();
        book.append("job-00001", JournalEvent.STARTED, NOW);
        book.append("job-00001", JournalEvent.FAILED, NOW, Map.of("reason", "cable"));
        assertThat(book.unfinished()).isEmpty();
    }

    @Test
    void aJobThatWasRetriedAndLeftOpenShowsUp() {
        JobJournal book = journal();
        book.append("job-00001", JournalEvent.STARTED, NOW);
        book.append("job-00001", JournalEvent.FAILED, NOW.plusSeconds(10), Map.of("reason", "cable"));
        book.append("job-00001", JournalEvent.STARTED, NOW.plusSeconds(20));
        assertThat(book.unfinished()).containsExactly("job-00001");
    }

    @Test
    void aStepLeavesAJobOpen() {
        JobJournal book = journal();
        book.append("job-00001", JournalEvent.STEP, NOW, Map.of("step", "erase"));
        assertThat(book.unfinished()).containsExactly("job-00001");
    }

    @Test
    void severalOpenJobsComeBackSorted() {
        JobJournal book = journal();
        book.append("job-00002", JournalEvent.STARTED, NOW);
        book.append("job-00001", JournalEvent.STARTED, NOW);
        assertThat(book.unfinished()).containsExactly("job-00001", "job-00002");
    }

    @Test
    void theBoardsAJournalMentions() {
        JobJournal book = journal();
        book.append("job-00001", JournalEvent.STARTED, NOW, Map.of("board", "bx-14"));
        book.append("job-00002", JournalEvent.STARTED, NOW, Map.of("board", "bx-15"));
        book.append("job-00002", JournalEvent.STEP, NOW, Map.of("step", "erase"));
        assertThat(book.boardsTouched()).containsExactly("bx-14", "bx-15");
    }

    @Test
    void anEntryDescribesItself() {
        JournalEntry entry = new JournalEntry(NOW, "job-00001", JournalEvent.STEP, Map.of("step", "erase"));
        assertThat(entry.describe()).isEqualTo("2025-10-16T09:00:00+00:00 job-00001 step step=erase");
    }

    @Test
    void rotationKeepsTheTail() {
        JobJournal book = journal();
        for (int index = 0; index < 20; index++) {
            book.append("job-00001", JournalEvent.STEP, NOW, Map.of("index", index));
        }
        int dropped = book.rotate(5);
        assertThat(dropped).isEqualTo(15);
        assertThat(book.entries()).hasSize(5);
        assertThat(book.entries().getLast().detail()).containsEntry("index", 19L);
    }

    @Test
    void rotationLeavesAShortJournalAlone() {
        JobJournal book = journal();
        book.append("job-00001", JournalEvent.STEP, NOW);
        assertThat(book.rotate(100)).isZero();
        assertThat(book.entries()).hasSize(1);
    }

    @Test
    void rotatingAJournalThatDoesNotExistDoesNothing() {
        assertThat(journal().rotate()).isZero();
    }

    @Test
    void keepingNothingIsRefused() {
        assertThatThrownBy(() -> journal().rotate(0)).isInstanceOf(ValidationError.class);
    }
}
