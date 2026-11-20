package io.github.rkaushikdev.carrierworks.services;

import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardBook;
import io.github.rkaushikdev.carrierworks.inventory.BoardJson;
import io.github.rkaushikdev.carrierworks.persistence.UnitOfWork;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reading a bench description into the database, and writing it back out.
 *
 * <p>Boards arrive as a JSON file. Somebody racks four carriers, writes down what went where, and
 * the file is what the bench is told. Doing that by hand through the board commands is four long
 * command lines with an asset tag in each, which is exactly the shape of thing that gets one
 * character wrong. The file is read by {@link io.github.rkaushikdev.carrierworks.inventory.BenchFile},
 * which this sits on top of.
 *
 * <p>Importing is deliberately not a merge. A board already on the bench is left alone unless the
 * caller says to replace it, because the common mistake is running the file twice, and the
 * expensive mistake is having the second run quietly overwrite a location somebody corrected by
 * hand last week.
 */
public final class BenchFile {

    private BenchFile() {}

    /** {@link #importBench(UnitOfWork, String, Instant, boolean)} leaving boards already there alone. */
    public static ImportOutcome importBench(UnitOfWork unit, String text, Instant now) {
        return importBench(unit, text, now, false);
    }

    /**
     * Put every board in a bench file onto the bench, each in the state the file gives it.
     *
     * @param replace whether a board already on the bench is written over rather than left alone
     * @throws io.github.rkaushikdev.carrierworks.ConfigurationError when the text is not a bench file
     */
    public static ImportOutcome importBench(UnitOfWork unit, String text, Instant now, boolean replace) {
        BoardBook book = io.github.rkaushikdev.carrierworks.inventory.BenchFile.read(text, now);
        List<String> added = new ArrayList<>();
        List<String> replaced = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Board board : book.all()) {
            if (!known(unit, board.tag())) {
                Inventory.addBoard(unit, board, now, book.state(board.tag()).state());
                added.add(board.tag());
            } else if (replace) {
                unit.boards().replace(board);
                replaced.add(board.tag());
            } else {
                skipped.add(board.tag());
            }
        }
        return new ImportOutcome(added, replaced, skipped);
    }

    /** {@link #exportBench(UnitOfWork, String)} naming the default site. */
    public static Map<String, Object> exportBench(UnitOfWork unit) {
        return exportBench(unit, io.github.rkaushikdev.carrierworks.inventory.BenchFile.DEFAULT_SITE);
    }

    /**
     * Every board on the bench, in the shape the bench file is read in.
     *
     * <p>The state goes out with each board, which the reader accepts and which means a file written
     * here and read back somewhere else describes the same bench rather than a bench where
     * everything is available.
     */
    public static Map<String, Object> exportBench(UnitOfWork unit, String site) {
        List<Map<String, Object>> boards = unit.boards().all().stream()
                .map(board -> {
                    Map<String, Object> entry = new LinkedHashMap<>(BoardJson.toJson(board));
                    entry.put("state", unit.boards().state(board.tag()).state().value());
                    return entry;
                })
                .toList();
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("site", site);
        document.put("boards", boards);
        return document;
    }

    /**
     * The text of a bench file.
     *
     * @throws NotFound when there is no file there
     * @throws UncheckedIOException when the file is there and cannot be read
     */
    public static String readFile(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new NotFound("bench file", path.toString());
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static boolean known(UnitOfWork unit, String tag) {
        try {
            unit.boards().get(tag);
            return true;
        } catch (NotFound missing) {
            return false;
        }
    }
}
