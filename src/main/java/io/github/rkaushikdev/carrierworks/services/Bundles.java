package io.github.rkaushikdev.carrierworks.services;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.artifacts.ArtifactRecord;
import io.github.rkaushikdev.carrierworks.artifacts.ArtifactStore;
import io.github.rkaushikdev.carrierworks.artifacts.Provenance;
import io.github.rkaushikdev.carrierworks.jobs.ProgramJob;
import io.github.rkaushikdev.carrierworks.persistence.UnitOfWork;
import io.github.rkaushikdev.carrierworks.reports.BuildReport;
import io.github.rkaushikdev.carrierworks.util.Digests;
import io.github.rkaushikdev.carrierworks.util.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Writing out everything the bench knows about one image, as files.
 *
 * <p>A bundle is what somebody hands to whoever is signing off a release: the image itself, the
 * reports that were read for it, where it came from, and every load of it onto a board with what
 * happened. The image goes in as the configuration bytes rather than as the {@code .bit} container
 * it arrived in, because those are the bytes that were written to the part and the container header
 * is metadata the manifest already carries. Nothing in it is new information and that is the point.
 * It is the same rows, written to a directory that can be attached to a ticket and read in two
 * years by somebody with no access to this bench at all.
 *
 * <p>The manifest lists every file with its SHA-256, and the manifest itself is hashed into the
 * result. That is not a signature and it is not pretending to be: it is enough to notice a bundle
 * that has been edited or truncated in transit, which is the failure that actually happens.
 */
public final class Bundles {

    /** The name of the file listing every other file in a bundle. */
    public static final String MANIFEST = "manifest.json";

    private static final int SHORT_DIGEST_LENGTH = 12;
    private static final Json.Writer WRITER = Json.writer().indent(2).sortKeys().textFallback();

    private Bundles() {}

    /** {@link #assemble(UnitOfWork, String, Path, Instant, ArtifactStore, Collection)} with no approvals. */
    public static Bundle assemble(UnitOfWork unit, String digest, Path into, Instant now, ArtifactStore store) {
        return assemble(unit, digest, into, now, store, List.of());
    }

    /**
     * Write the bundle for one image into a directory of its own under {@code into}.
     *
     * <p>The directory is named after the image rather than after the design, because two designs
     * with the same name and different bytes is the exact situation a bundle is meant to settle.
     *
     * @param approvals who has signed the image off; repeats count once
     * @throws NotFound when the image is not known, or its bytes are not in the store
     * @throws ValidationError when the bundle's directory already has something in it
     * @throws UncheckedIOException when a file cannot be written
     */
    public static Bundle assemble(
            UnitOfWork unit, String digest, Path into, Instant now, ArtifactStore store, Collection<String> approvals) {
        ArtifactRecord record = unit.artifacts().get(digest);
        if (!store.has(record.digest())) {
            throw new NotFound("image bytes", record.digest());
        }
        Path root = into.resolve(record.digest().substring(0, SHORT_DIGEST_LENGTH));
        if (Files.exists(root) && !isEmpty(root)) {
            throw new ValidationError(
                    "that bundle directory is not empty", "into", Details.of("path", root.toString()));
        }
        createDirectories(root);

        List<ProgramJob> jobs = unit.jobs().forDigest(record.digest());
        Optional<BuildReport> report = unit.reports().forDigest(record.digest());
        List<Provenance> builds = unit.provenance().forDigest(record.digest());

        Map<String, String> hashes = new TreeMap<>();
        hashes.put("image.bin", write(root, "image.bin", store.read(record.digest())));
        hashes.put("artifact.json", write(root, "artifact.json", record.toJson()));
        hashes.put(
                "jobs.json",
                write(root, "jobs.json", asJson(jobs.stream().map(Bundles::load).toList())));
        hashes.put(
                "provenance.json",
                write(
                        root,
                        "provenance.json",
                        asJson(builds.stream()
                                .map(build -> Json.parse(build.toJson()))
                                .toList())));
        report.ifPresent(found -> hashes.put("reports.json", write(root, "reports.json", asJson(numbers(found)))));

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("digest", record.digest());
        manifest.put("design", record.design());
        manifest.put("device", record.device());
        manifest.put("channel", record.channel());
        manifest.put("written_at", now);
        manifest.put("approvals", List.copyOf(new TreeSet<>(approvals)));
        manifest.put("files", hashes);
        String manifestDigest = write(root, MANIFEST, asJson(manifest));

        List<String> files = new ArrayList<>(hashes.keySet());
        files.add(MANIFEST);
        files.sort(null);
        return new Bundle(
                record.digest(), root, files, manifestDigest, jobs.size(), report.isPresent() ? 1 : 0, builds.size());
    }

    /**
     * Every file in a bundle that does not match its manifest entry, in name order.
     *
     * <p>A missing file and a changed file are both reported, so a truncated copy reads as clearly as
     * an edited one. A bundle that matches its manifest gives back nothing.
     *
     * @throws NotFound when the directory has no manifest
     * @throws ValidationError when the manifest is not an object with an object of files in it
     * @throws Json.MalformedJsonException when the manifest is not JSON
     */
    public static List<String> verify(Path root) {
        Path manifestPath = root.resolve(MANIFEST);
        if (!Files.isRegularFile(manifestPath)) {
            throw new NotFound("bundle manifest", root.toString());
        }
        if (!(Json.parse(read(manifestPath)) instanceof Map<?, ?> manifest)) {
            throw unreadable(root);
        }
        Object files = manifest.containsKey("files") ? manifest.get("files") : Map.of();
        if (!(files instanceof Map<?, ?> entries)) {
            throw unreadable(root);
        }
        Map<String, Object> listed = new TreeMap<>();
        entries.forEach((name, expected) -> listed.put(String.valueOf(name), expected));
        List<String> problems = new ArrayList<>();
        listed.forEach((name, expected) -> {
            Path path = root.resolve(name);
            if (!Files.isRegularFile(path)) {
                problems.add(name + " is missing");
            } else if (!Digests.sha256(readBytes(path)).equals(expected)) {
                problems.add(name + " does not match the manifest");
            }
        });
        return List.copyOf(problems);
    }

    private static ValidationError unreadable(Path root) {
        return new ValidationError(
                "that bundle manifest does not list its files", "manifest", Details.of("path", root.toString()));
    }

    /** One load of the image, as the bundle lists it. */
    private static Map<String, Object> load(ProgramJob job) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", job.id());
        entry.put("board", job.board());
        entry.put("mode", job.mode().value());
        entry.put("state", job.state().value());
        entry.put("attempts", job.attempts());
        entry.put("requested_by", job.requestedBy());
        entry.put("error", job.error());
        entry.put("error_code", job.errorCode());
        return entry;
    }

    /** The numbers out of the image's build report, as the bundle carries them. */
    private static Map<String, Object> numbers(BuildReport report) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("design", report.design());
        entry.put("read_at", report.at());
        entry.put("revision", report.revision());
        entry.put("closed", report.closed());
        entry.put("timing", report.timing().describe());
        entry.put("worst_slack_ps", report.timing().wnsPs());
        entry.put("utilization", report.utilization().describe());
        return entry;
    }

    private static String asJson(Object payload) {
        return WRITER.write(payload) + "\n";
    }

    /** Write one file and hand back its SHA-256. */
    private static String write(Path root, String name, String payload) {
        return write(root, name, payload.getBytes(StandardCharsets.UTF_8));
    }

    private static String write(Path root, String name, byte[] payload) {
        try {
            Files.write(root.resolve(name), payload);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return Digests.sha256(payload);
    }

    private static boolean isEmpty(Path folder) {
        if (!Files.isDirectory(folder)) {
            return false;
        }
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.findAny().isEmpty();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void createDirectories(Path folder) {
        try {
            Files.createDirectories(folder);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static byte[] readBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
