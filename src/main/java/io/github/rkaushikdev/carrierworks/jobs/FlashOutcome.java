package io.github.rkaushikdev.carrierworks.jobs;

import java.util.Objects;

/**
 * What one flash write did.
 *
 * @param region the region that was written
 * @param bytesWritten how many bytes the page programs covered
 * @param erases how many erases were sent
 * @param pages how many page programs were sent
 * @param verified whether the image was read back and compared
 */
public record FlashOutcome(String region, long bytesWritten, int erases, int pages, boolean verified) {

    public FlashOutcome {
        Objects.requireNonNull(region, "region");
    }

    /** {@code update: 4096 bytes in 16 pages, 1 erases, verified}. */
    public String describe() {
        String checked = verified ? "verified" : "not verified";
        return region + ": " + bytesWritten + " bytes in " + pages + " pages, " + erases + " erases, " + checked;
    }
}
