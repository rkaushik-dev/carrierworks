package io.github.rkaushikdev.carrierworks.artifacts;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.Ids;
import io.github.rkaushikdev.carrierworks.ValidationError;

/**
 * The channels an image moves through, ordered by trust, least first.
 *
 * <p>The difference between them is trust rather than content. Anything a build produces lands in
 * dev. An image that has closed timing and can be rebuilt from a clean tree may be promoted to
 * qual, which is what the nightly regression runs from. Golden is the image that goes into the
 * flash on a board that ships to a customer demo, and getting there needs a person to say so.
 */
public enum Channel {
    DEV("dev"),
    QUAL("qual"),
    GOLDEN("golden");

    private final String value;

    Channel(String value) {
        this.value = value;
    }

    /** The name the channel is stored, printed and asked for under. */
    public String value() {
        return value;
    }

    /** How trusted the channel is, least first. */
    public int rank() {
        return ordinal();
    }

    /** The channel with this name, however it was written. */
    public static Channel parse(String text) {
        String name = Ids.slug(text);
        for (Channel channel : values()) {
            if (channel.value.equals(name)) {
                return channel;
            }
        }
        throw new ValidationError("no such channel", "channel", Details.of("given", text));
    }

    @Override
    public String toString() {
        return value;
    }
}
