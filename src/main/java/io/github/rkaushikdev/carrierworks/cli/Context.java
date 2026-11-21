package io.github.rkaushikdev.carrierworks.cli;

import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import io.github.rkaushikdev.carrierworks.persistence.UnitOfWork;
import io.github.rkaushikdev.carrierworks.util.Json;
import io.github.rkaushikdev.carrierworks.util.Repr;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What every command is handed: the settings, the database, and the terminal to answer on.
 *
 * <p>Every listing prints one of two ways. The default is columns for a person, and {@code --json}
 * is the same data for a script; neither is a reformatting of the other, because both come off the
 * same rows. A single result is a sentence, or its payload as JSON.
 *
 * <p>The database is opened the first time a command asks for a unit of work, so a command that
 * never touches it never connects, and it is closed with the context.
 */
final class Context implements AutoCloseable {

    private static final Json.Writer JSON = Json.writer().indent(2).textFallback();

    private final BenchSettings settings;
    private final boolean asJson;
    private final BufferedReader in;
    private final PrintWriter out;
    private BenchDatabase database;

    Context(BenchSettings settings, boolean asJson, Terminal terminal) {
        this.settings = settings;
        this.asJson = asJson;
        this.in = terminal.in();
        this.out = terminal.out();
    }

    /** The settings, with the database the command line named. */
    BenchSettings settings() {
        return settings;
    }

    /** Whether the answer is wanted as JSON. */
    boolean asJson() {
        return asJson;
    }

    /** The moment by the real clock, read afresh each time. */
    Instant now() {
        return Times.now();
    }

    /** Run work in a fresh unit of work, committing unless it throws, and hand back its answer. */
    <T> T apply(Function<? super UnitOfWork, ? extends T> work) {
        return database().apply(work);
    }

    /** Run work in a fresh unit of work, committing unless it throws. */
    void run(Consumer<? super UnitOfWork> work) {
        database().run(work);
    }

    /** What the terminal is typing in, for a command told to read standard input. */
    BufferedReader in() {
        return in;
    }

    /**
     * Print a listing, as JSON or as columns.
     *
     * <p>Columns are as wide as their widest value or their name, whichever is longer, each padded
     * with spaces and two spaces apart, under a heading of the names in capitals. A missing value
     * prints as nothing.
     */
    void emit(List<? extends Map<String, ?>> rows, String... columns) {
        if (asJson) {
            json(rows);
            return;
        }
        if (rows.isEmpty()) {
            echo("nothing to show");
            return;
        }
        Map<String, Integer> widths = new LinkedHashMap<>();
        for (String name : columns) {
            int widest = length(name);
            for (Map<String, ?> row : rows) {
                widest = Math.max(widest, length(cell(row, name)));
            }
            widths.put(name, widest);
        }
        echo(List.of(columns).stream()
                .map(name -> pad(name.toUpperCase(Locale.ROOT), widths.get(name)))
                .collect(Collectors.joining("  ")));
        for (Map<String, ?> row : rows) {
            echo(List.of(columns).stream()
                    .map(name -> pad(cell(row, name), widths.get(name)))
                    .collect(Collectors.joining("  ")));
        }
    }

    /**
     * Print one result, as JSON or as a sentence.
     *
     * @param payload what the JSON form carries; an empty one stands for {@code {"message": ...}}
     */
    void say(String message, Map<String, ?> payload) {
        if (asJson) {
            json(payload.isEmpty() ? Map.of("message", message) : payload);
        } else {
            echo(message);
        }
    }

    /** Print a value as indented JSON on its own lines. */
    void json(Object value) {
        echo(JSON.write(value));
    }

    /** Print one line. */
    void echo(String line) {
        out.print(line);
        out.print('\n');
    }

    /** Print text exactly as given, with no line end added. */
    void write(String text) {
        out.print(text);
    }

    private BenchDatabase database() {
        if (database == null) {
            database = new BenchDatabase(settings);
        }
        return database;
    }

    /**
     * One value as a column prints it: text as itself, a moment in its listing form, anything else
     * in its quoted form.
     */
    private static String cell(Map<String, ?> row, String name) {
        if (!row.containsKey(name)) {
            return "";
        }
        return switch (row.get(name)) {
            case String text -> text;
            case Instant moment -> Times.text(moment);
            case null -> Repr.of(null);
            case Object value -> Repr.of(value);
        };
    }

    private static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    private static String pad(String text, int width) {
        return text + " ".repeat(Math.max(0, width - length(text)));
    }

    /** Close the database, if a command opened it. */
    @Override
    public void close() {
        if (database != null) {
            database.close();
        }
    }
}
