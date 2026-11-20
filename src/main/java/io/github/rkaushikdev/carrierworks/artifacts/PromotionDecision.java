package io.github.rkaushikdev.carrierworks.artifacts;

import java.util.List;
import java.util.Objects;

/**
 * Whether an image may move to another channel, and everything standing in the way.
 *
 * @param digest the image's digest
 * @param fromChannel where it is now
 * @param toChannel where it was asked to go
 * @param blockers every reason it may not, in the order the rules found them; none when it may
 */
public record PromotionDecision(String digest, Channel fromChannel, Channel toChannel, List<String> blockers) {

    public PromotionDecision {
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(fromChannel, "fromChannel");
        Objects.requireNonNull(toChannel, "toChannel");
        blockers = List.copyOf(blockers);
    }

    /** A move nothing stands in the way of. */
    public PromotionDecision(String digest, Channel fromChannel, Channel toChannel) {
        this(digest, fromChannel, toChannel, List.of());
    }

    /** Whether the image may move. */
    public boolean allowed() {
        return blockers.isEmpty();
    }

    /** {@code abababababab may go from dev to qual}, or why it cannot. */
    public String describe() {
        String move = fromChannel + " to " + toChannel;
        String image = digest.substring(0, Math.min(12, digest.length()));
        if (allowed()) {
            return image + " may go from " + move;
        }
        return image + " cannot go from " + move + ": " + String.join("; ", blockers);
    }
}
