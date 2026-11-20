package io.github.rkaushikdev.carrierworks.artifacts;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.Ids;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.bitstream.Bitstream;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * A content addressed store for images, and the records that describe them.
 *
 * <p>Addressing by digest settles three arguments at once. Two builds that produce the same image
 * are one entry rather than two, so the registry does not grow a copy every night for a design
 * nobody has touched. An image cannot be quietly replaced, because changing it changes where it
 * lives. And the digest on a job record is enough to fetch exactly what was loaded, months later,
 * without trusting a file name.
 *
 * <p>The store keeps the raw configuration data, not the {@code .bit} file it arrived in. The
 * header is a wrapper, the tools regenerate it, and two files whose headers differ only by a
 * timestamp are not two artifacts.
 *
 * <p>On disk an image lives under {@code <root>/<first two digits>/<the rest of the digest>/}, as
 * {@code image.bin} beside its {@code meta.json} record.
 */
public final class ArtifactStore {

    private static final String META = "meta.json";
    private static final String IMAGE = "image.bin";
    private static final Comparator<ArtifactRecord> NEWEST_FIRST = Comparator.comparing(ArtifactRecord::storedAt)
            .thenComparing(ArtifactRecord::digest)
            .reversed();

    private final Path root;
    private final Clock clock;

    /** A store rooted here, stamping records by the system clock. */
    public ArtifactStore(Path root) {
        this(root, Clock.systemUTC());
    }

    /** A store rooted here, stamping records by the clock given. */
    public ArtifactStore(Path root, Clock clock) {
        this.root = root;
        this.clock = clock;
    }

    /** Where the store keeps its images. */
    public Path root() {
        return root;
    }

    /** Where the image bytes for a digest live, whether or not they do. */
    public Path pathFor(String digest) {
        return folder(digest).resolve(IMAGE);
    }

    /** Whether the store holds the image with this digest. */
    public boolean has(String digest) {
        return Files.isRegularFile(pathFor(digest));
    }

    /** Store an image in the dev channel, or hand back the record for the one already there. */
    public ArtifactRecord put(Bitstream image) {
        return put(image, ArtifactRecord.DEFAULT_CHANNEL);
    }

    /**
     * Store an image, or hand back the record for the one already there.
     *
     * <p>Storing the same image twice is normal. A nightly build of an unchanged design produces the
     * same bytes, and the second store is a no operation rather than an error, so nobody has to
     * check first. The record already there keeps its own channel.
     */
    public ArtifactRecord put(Bitstream image, String channel) {
        ArtifactRecord record = ArtifactRecord.of(image, channel, Times.iso(Times.now(clock), Times.Precision.SECONDS));
        if (has(record.digest())) {
            return recordFor(record.digest());
        }
        Path folder = folder(record.digest());
        try {
            Files.createDirectories(folder);
            Files.write(folder.resolve(IMAGE), image.header().data());
            Files.writeString(folder.resolve(META), record.toJson(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return record;
    }

    /**
     * The record for a stored image.
     *
     * @throws NotFound when the store has no record with that digest
     */
    public ArtifactRecord recordFor(String digest) {
        Path meta = folder(digest).resolve(META);
        if (!Files.isRegularFile(meta)) {
            throw new NotFound("artifact", digest);
        }
        return ArtifactRecord.fromJson(text(meta));
    }

    /**
     * The configuration data of a stored image.
     *
     * @throws NotFound when the store has no image with that digest
     */
    public byte[] read(String digest) {
        Path path = pathFor(digest);
        if (!Files.isRegularFile(path)) {
            throw new NotFound("artifact", digest);
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /**
     * Write a changed record back, leaving the image alone.
     *
     * @throws NotFound when the store has no image with that digest
     */
    public ArtifactRecord update(ArtifactRecord record) {
        if (!has(record.digest())) {
            throw new NotFound("artifact", record.digest());
        }
        try {
            Files.writeString(folder(record.digest()).resolve(META), record.toJson(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return record;
    }

    /** Every record in the store, newest first. */
    public List<ArtifactRecord> records() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Path> metas;
        try (Stream<Path> paths = Files.walk(root, 3)) {
            metas = paths.filter(path -> root.relativize(path).getNameCount() == 3)
                    .filter(path -> path.getFileName().toString().equals(META))
                    .sorted()
                    .toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        List<ArtifactRecord> found = new ArrayList<>();
        for (Path meta : metas) {
            found.add(ArtifactRecord.fromJson(text(meta)));
        }
        found.sort(NEWEST_FIRST);
        return List.copyOf(found);
    }

    /** Every record for one design, however its name was written, newest first. */
    public List<ArtifactRecord> forDesign(String design) {
        String wanted = Ids.designName(design);
        return records().stream()
                .filter(record -> record.design().equals(wanted))
                .toList();
    }

    /**
     * Take an image and its record out of the store.
     *
     * @throws NotFound when the store has nothing under that digest
     */
    public void remove(String digest) {
        Path folder = folder(digest);
        if (!Files.isDirectory(folder)) {
            throw new NotFound("artifact", digest);
        }
        try (Stream<Path> children = Files.list(folder)) {
            for (Path child : children.toList()) {
                Files.delete(child);
            }
            Files.delete(folder);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private Path folder(String digest) {
        if (digest.length() < 4) {
            throw new ValidationError("that is not a digest", "digest", Details.of("given", digest));
        }
        return root.resolve(digest.substring(0, 2)).resolve(digest.substring(2));
    }

    private static String text(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
