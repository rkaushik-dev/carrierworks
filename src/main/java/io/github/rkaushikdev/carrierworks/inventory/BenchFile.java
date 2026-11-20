package io.github.rkaushikdev.carrierworks.inventory;

import static io.github.rkaushikdev.carrierworks.util.JsonValues.get;
import static io.github.rkaushikdev.carrierworks.util.JsonValues.text;

import io.github.rkaushikdev.carrierworks.ConfigurationError;
import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.util.Json;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reading the bench out of a file, and writing it back.
 *
 * <p>The inventory is kept in version control as one JSON document, because the lab's changes to
 * it are the same kind of thing as a change to the code: a person did something physical, and
 * everyone else needs to know. A pull request with "moved bx-14 to rack 4 and put the sfp card on
 * it" is exactly the record that was missing when this was a spreadsheet.
 *
 * <p>Errors name the board and the field. A file with forty boards in it and a message saying a
 * value is wrong somewhere is not much better than no message, so every failure carries the tag it
 * was reading when it gave up.
 *
 * <p>The document is an object with a {@code site} and a list of {@code boards}, each a {@link
 * BoardJson} object with its {@code state} and, optionally, the {@code reason} for it. It is
 * written indented by two spaces with every object's keys sorted and a final newline, so a change
 * to one board is a small diff.
 */
public final class BenchFile {

    /** The site a dumped document names unless the caller gives another. */
    public static final String DEFAULT_SITE = "lab-a";

    private static final Json.Writer WRITER = Json.writer().indent(2).sortKeys();

    private BenchFile() {}

    /** {@link #read(String, Instant)} with every board arriving now. */
    public static BoardBook read(String text) {
        return read(text, Times.now());
    }

    /**
     * Build a board book out of a JSON document, every board arriving at {@code when}.
     *
     * @throws ConfigurationError when the text is not JSON, is not an object holding a list of
     *     boards, or a board in it cannot be read or collides with another; a board that cannot be
     *     read is named by its tag and its position in the list
     * @throws ValidationError when a board's state is not one a board can be in
     */
    public static BoardBook read(String text, Instant when) {
        Object raw;
        try {
            raw = Json.parse(text);
        } catch (Json.MalformedJsonException failure) {
            throw new ConfigurationError(
                    "the bench file is not json", Details.of("detail", failure.getMessage()), failure);
        }
        if (!(raw instanceof Map<?, ?> document)) {
            throw new ConfigurationError("the bench file holds an object");
        }
        if (!(document.get("boards") instanceof List<?> boards)) {
            throw new ConfigurationError("the bench file needs a list of boards");
        }
        BoardBook book = new BoardBook(when);
        for (int index = 0; index < boards.size(); index++) {
            if (!(boards.get(index) instanceof Map<?, ?> entry)) {
                throw new ConfigurationError("a board entry is an object", Details.of("position", index));
            }
            String tag = text(get(entry, "asset", "entry " + index));
            Board board;
            try {
                board = BoardJson.fromJson(entry);
            } catch (ConfigurationError failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw new ConfigurationError(
                        "that board could not be read: " + Objects.requireNonNullElse(failure.getMessage(), ""),
                        Details.of("board", tag, "position", index),
                        failure);
            }
            BoardStatus state = BoardStatus.parse(text(get(entry, "state", BoardStatus.AVAILABLE.value())));
            book.add(board, when, state, text(get(entry, "reason", "")));
        }
        return book;
    }

    /** {@link #load(Path, Instant)} with every board arriving now. */
    public static BoardBook load(Path path) {
        return load(path, Times.now());
    }

    /**
     * Read the bench file at {@code path}, which is UTF-8.
     *
     * @throws ConfigurationError when there is no file there, or as {@link #read(String, Instant)}
     * @throws UncheckedIOException when the file is there but cannot be read
     */
    public static BoardBook load(Path path, Instant when) {
        if (!Files.isRegularFile(path)) {
            throw new ConfigurationError("no bench file there", Details.of("path", path.toString()));
        }
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return read(text, when);
    }

    /** {@link #dump(BoardBook, String)} for the default site. */
    public static String dump(BoardBook book) {
        return dump(book, DEFAULT_SITE);
    }

    /** The book back out as a JSON document, in the same shape it is read from. */
    public static String dump(BoardBook book, String site) {
        List<Map<String, Object>> boards = book.all().stream()
                .map(board -> {
                    Map<String, Object> entry = new LinkedHashMap<>(BoardJson.toJson(board));
                    entry.put("state", book.state(board.tag()).state().value());
                    return entry;
                })
                .toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("site", site);
        payload.put("boards", boards);
        return WRITER.write(payload) + "\n";
    }
}
