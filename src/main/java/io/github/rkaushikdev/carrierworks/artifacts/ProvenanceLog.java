package io.github.rkaushikdev.carrierworks.artifacts;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.util.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Every build that has produced a given image.
 *
 * <p>Kept beside the image in the artifact store, one {@code provenance.json} file holding a list,
 * because the interesting question is always all of them rather than the latest one.
 */
public final class ProvenanceLog {

    private static final String FILE = "provenance.json";

    private final Path root;

    /** The log kept in an artifact store rooted here. */
    public ProvenanceLog(Path root) {
        this.root = root;
    }

    /** The artifact store the log is kept in. */
    public Path root() {
        return root;
    }

    /**
     * Add a build, unless an identical one is already recorded, and hand back every build of the
     * image.
     *
     * @throws NotFound when the store holds no image with that digest
     */
    public List<Provenance> record(Provenance provenance) {
        Path path = path(provenance.digest());
        if (!Files.isDirectory(path.getParent())) {
            throw new NotFound("artifact", provenance.digest());
        }
        List<Provenance> existing = forDigest(provenance.digest());
        if (existing.contains(provenance)) {
            return existing;
        }
        List<Provenance> updated = new ArrayList<>(existing);
        updated.add(provenance);
        String text = Json.writer()
                .indent(2)
                .sortKeys()
                .write(updated.stream().map(Provenance::asMap).toList());
        try {
            Files.writeString(path, text, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return List.copyOf(updated);
    }

    /** Every build recorded for an image, oldest first; none when nothing has been recorded. */
    public List<Provenance> forDigest(String digest) {
        Path path = path(digest);
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        Object raw;
        try {
            raw = Json.parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        if (!(raw instanceof List<?> entries)) {
            throw new IllegalArgumentException("a provenance file is a list of builds: " + path);
        }
        return entries.stream().map(Provenance::fromParsed).toList();
    }

    /** Every source revision that has produced this image, sorted. */
    public List<String> revisions(String digest) {
        return List.copyOf(new TreeSet<>(
                forDigest(digest).stream().map(Provenance::sourceRevision).toList()));
    }

    /**
     * Builds of this image whose inputs differ from the first one recorded.
     *
     * <p>Not an error. It is how a rebuild on a different branch shows up, and the operator wants
     * to see it rather than have it hidden.
     */
    public List<String> disagreements(String digest) {
        List<Provenance> builds = forDigest(digest);
        if (builds.size() < 2) {
            return List.of();
        }
        Provenance first = builds.getFirst();
        return builds.stream()
                .skip(1)
                .filter(build -> !build.sameSourceAs(first))
                .map(Provenance::buildId)
                .toList();
    }

    private Path path(String digest) {
        if (digest.length() < 4) {
            throw new ValidationError("that is not a digest", "digest", Details.of("given", digest));
        }
        return root.resolve(digest.substring(0, 2)).resolve(digest.substring(2)).resolve(FILE);
    }
}
