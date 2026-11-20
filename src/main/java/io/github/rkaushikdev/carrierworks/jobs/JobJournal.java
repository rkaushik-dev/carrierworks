package io.github.rkaushikdev.carrierworks.jobs;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.Ids;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * An append only record of everything the runner did, written down as it happens, one line at a
 * time.
 *
 * <p>The bench crashes. A worker gets killed, a machine reboots, somebody closes the laptop the
 * poller was running on. When it comes back, the question that matters is which board was in the
 * middle of being programmed, because that board is in an unknown state and the worst thing to do
 * with it is to hand it to somebody else.
 *
 * <p>So every step is appended to a file before it is attempted, not after. A line saying a flash
 * erase started with no line saying it finished is exactly the signal the recovery needs, and it can
 * only exist if the write comes first.
 *
 * <p>One JSON object per line ({@link JournalEntry#toLine()}), and the file is closed after every
 * write. It is not fast and it does not need to be: the whole bench writes a few hundred lines an
 * hour, and being able to read the file with an editor while a job is running has been worth more
 * than any amount of speed.
 *
 * <p>A file that cannot be read or written raises {@link UncheckedIOException}.
 */
public final class JobJournal {

    /** How many lines a rotation keeps unless it is told otherwise. */
    public static final int DEFAULT_KEEP_LINES = 5000;

    private final Path path;

    /** A journal in this file, which need not exist yet. */
    public JobJournal(Path path) {
        this.path = path;
    }

    /** The file the journal lives in. */
    public Path path() {
        return path;
    }

    /** Write one line, before whatever it describes happens, creating the file and its folders. */
    public JournalEntry record(JournalEntry entry) {
        String line = entry.toLine() + "\n";
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            Files.writeString(path, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return entry;
    }

    /** Write a line with nothing beside the event. */
    public JournalEntry append(String job, JournalEvent event, Instant at) {
        return record(new JournalEntry(at, job, event));
    }

    /** Write a line with some detail beside the event. */
    public JournalEntry append(String job, JournalEvent event, Instant at, Map<String, ?> detail) {
        return record(new JournalEntry(at, job, event, new LinkedHashMap<>(detail)));
    }

    /** Every line in the file, in the order written; no file yet means no lines. */
    public List<JournalEntry> entries() {
        return lines().stream().map(JournalEntry::fromLine).toList();
    }

    /** One job's lines, in the order written. */
    public List<JournalEntry> forJob(String job) {
        String wanted = Ids.slug(job);
        return entries().stream().filter(entry -> entry.job().equals(wanted)).toList();
    }

    /** The last line written about a job, if there is one. */
    public Optional<JournalEntry> lastEvent(String job) {
        List<JournalEntry> found = forJob(job);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.getLast());
    }

    /**
     * Jobs that started and never wrote a line saying how they ended, sorted.
     *
     * <p>These are the ones whose boards are in an unknown state after a crash, and they are the
     * only reason this file exists. A job that failed, was retried and started again is open again.
     */
    public List<String> unfinished() {
        Map<String, Boolean> open = new HashMap<>();
        for (JournalEntry entry : entries()) {
            if (entry.event().leavesOpen()) {
                open.put(entry.job(), true);
            } else if (entry.closes()) {
                open.put(entry.job(), false);
            }
        }
        return open.entrySet().stream()
                .filter(Map.Entry::getValue)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    /** Every board any line names in its detail, sorted. */
    public List<String> boardsTouched() {
        TreeSet<String> boards = new TreeSet<>();
        for (JournalEntry entry : entries()) {
            if (entry.detail().containsKey("board")) {
                boards.add(JournalEntry.text(entry.detail().get("board")));
            }
        }
        return List.copyOf(boards);
    }

    /** {@link #rotate(int)} keeping the last {@value #DEFAULT_KEEP_LINES} lines. */
    public int rotate() {
        return rotate(DEFAULT_KEEP_LINES);
    }

    /**
     * Trim the file to its last lines, returning how many went.
     *
     * <p>Called by the daily report rather than on a timer, because a journal that rotates while a
     * job is running is a journal missing the lines that matter. Blank lines go too.
     */
    public int rotate(int keepLines) {
        if (keepLines < 1) {
            throw new ValidationError("keep some lines", "keep_lines", Details.of("given", keepLines));
        }
        List<String> lines = lines();
        if (lines.size() <= keepLines) {
            return 0;
        }
        List<String> kept = lines.subList(lines.size() - keepLines, lines.size());
        try {
            Files.writeString(path, String.join("\n", kept) + "\n", StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return lines.size() - kept.size();
    }

    /** The file's lines that are not blank; no file yet means none. */
    private List<String> lines() {
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8)
                    .lines()
                    .filter(line -> !line.isBlank())
                    .toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
