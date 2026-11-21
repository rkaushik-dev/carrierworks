package io.github.rkaushikdev.carrierworks.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

/**
 * A terminal to type commands at, pointed at one database.
 *
 * <p>Every command goes through {@link Main#run} with {@code --database} in front of it, an
 * environment of the test's own, and both output streams caught in one writer, in the order they
 * were written, which is how somebody at the terminal sees them. Anything a command throws that
 * is not a bench error comes straight out of {@link #run}, so it fails the test.
 */
final class Shell {

    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final String url;
    private final Map<String, String> environment = new LinkedHashMap<>();

    Shell(String url) {
        this.url = url;
    }

    /** The URL of a file database called {@code name} in a directory. */
    static String url(Path directory, String name) {
        return "jdbc:h2:file:" + directory.resolve(name).toAbsolutePath();
    }

    /** {@link #url(Path, String)} for a database called {@code bench}. */
    static String url(Path directory) {
        return url(directory, "bench");
    }

    /** Settings for the database at a URL, everything else as it comes. */
    static BenchSettings settings(String url) {
        return BenchSettings.builder().databaseUrl(url).build();
    }

    /** A database at a URL with its schema built. The caller closes it. */
    static BenchDatabase migrated(String url) {
        BenchDatabase database = new BenchDatabase(settings(url));
        database.createSchema();
        return database;
    }

    /** One variable in the environment of every command typed from now on. */
    Shell env(String name, String value) {
        environment.put(name, value);
        return this;
    }

    /** Type a command at this shell's database. */
    Result run(String... args) {
        return type("", args);
    }

    /** Type a command at this shell's database with something waiting on standard input. */
    Result input(String text, String... args) {
        return type(text, args);
    }

    private Result type(String input, String... args) {
        List<String> line = new ArrayList<>(List.of("--database", url));
        line.addAll(List.of(args));
        return typeBare(environment, input, line.toArray(String[]::new));
    }

    /** Type a command line exactly as given, with no database in front of it. */
    static Result typeBare(Map<String, String> environment, String input, String... args) {
        StringWriter both = new StringWriter();
        PrintWriter writer = new PrintWriter(both);
        Terminal terminal = new Terminal(environment, new BufferedReader(new StringReader(input)), writer, writer);
        int status = Main.run(terminal, args);
        writer.flush();
        return new Result(status, both.toString());
    }

    /** The values of one field across a JSON array of objects, in order, as text. */
    static List<String> column(JsonNode rows, String field) {
        return StreamSupport.stream(rows.spliterator(), false)
                .map(row -> row.get(field).asText())
                .toList();
    }

    /** The strings in a JSON array, in order. */
    static List<String> texts(JsonNode items) {
        return StreamSupport.stream(items.spliterator(), false)
                .map(JsonNode::asText)
                .toList();
    }

    /**
     * What a command did.
     *
     * @param exitCode its exit status
     * @param output everything it printed, errors included
     */
    record Result(int exitCode, String output) {

        /** The output, read as JSON. */
        JsonNode json() {
            try {
                return JSON.readTree(output);
            } catch (JsonProcessingException failure) {
                throw new UncheckedIOException("not JSON: " + output, failure);
            }
        }
    }
}
