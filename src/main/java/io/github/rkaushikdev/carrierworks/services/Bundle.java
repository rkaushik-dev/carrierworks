package io.github.rkaushikdev.carrierworks.services;

import java.nio.file.Path;
import java.util.List;

/**
 * One written bundle, and what went into it.
 *
 * @param digest the image's full digest
 * @param root the directory the bundle was written into
 * @param files every file in it, the manifest included, in name order
 * @param manifestDigest the SHA-256 of the manifest, as 64 hex digits
 * @param jobs how many loads of the image it lists
 * @param reports how many build reports it carries, one or none
 * @param builds how many recorded builds it lists
 */
public record Bundle(
        String digest, Path root, List<String> files, String manifestDigest, int jobs, int reports, int builds) {

    private static final int SHORT_DIGEST_LENGTH = 12;

    public Bundle {
        files = List.copyOf(files);
    }

    /** Where the manifest is. */
    public Path manifestPath() {
        return root.resolve(Bundles.MANIFEST);
    }

    /** {@code 0123456789ab bundled to out/0123456789ab: 5 files, 0 loads, 0 reports}. */
    public String describe() {
        return digest.substring(0, SHORT_DIGEST_LENGTH) + " bundled to " + root + ": " + files.size() + " files, "
                + jobs + " loads, " + reports + " reports";
    }
}
