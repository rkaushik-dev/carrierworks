package io.github.rkaushikdev.carrierworks.bitstream;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The moment the tools wrote into an image's {@code USR_ACCESS} register.
 *
 * <p>Built with {@code USR_ACCESS TIMESTAMP}, an image carries its own build time in the
 * {@code axss} register, and the running design can read it back through the {@code USR_ACCESSE2}
 * primitive. That makes it the one build time that survives everything: it is in the configuration
 * data, so it is covered by the digest, it is still there after the header has been stripped for a
 * flash write, and the board will report it from the rack. The header's date and time fields are
 * text a tool wrote and any copy can change.
 *
 * <p>The packing is fixed: five bits of day, four of month, six of years since 2000, five of hour,
 * six of minute and six of second, most significant first. A {@code USR_ACCESS} that holds a plain
 * constant instead does not read as a date and is reported as no stamp at all.
 *
 * @param at the build time, to the second, in whatever zone the build machine was in
 */
public record BuildStamp(LocalDateTime at) {

    private static final int AXSS = 13;
    private static final DateTimeFormatter PRINTED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    /** The stamp a {@code USR_ACCESS} word holds, or nothing when it is not a timestamp. */
    public static Optional<BuildStamp> decode(long word) {
        try {
            return Optional.of(new BuildStamp(LocalDateTime.of(
                    2000 + (int) ((word >>> 17) & 0x3F),
                    (int) ((word >>> 23) & 0xF),
                    (int) ((word >>> 27) & 0x1F),
                    (int) ((word >>> 12) & 0x1F),
                    (int) ((word >>> 6) & 0x3F),
                    (int) (word & 0x3F))));
        } catch (DateTimeException notADate) {
            return Optional.empty();
        }
    }

    /** The stamp an image's packet stream carries in its last {@code axss} write, if it is one. */
    public static Optional<BuildStamp> in(List<ConfigPacket> packets) {
        List<Long> written = packets.stream()
                .filter(packet -> packet.isWrite() && packet.register().equals(Optional.of(AXSS)))
                .flatMap(packet -> packet.words().stream())
                .toList();
        return written.isEmpty() ? Optional.empty() : decode(written.getLast());
    }

    /** The stamp the configuration data a store keeps carries, if any. */
    public static Optional<BuildStamp> of(byte[] configData) {
        return in(Packets.parse(configData));
    }

    /**
     * The {@code USR_ACCESS} word for a moment, which is how the tests get one.
     *
     * @throws ValidationError for a year the six bit field cannot hold
     */
    public long encode() {
        int years = at.getYear() - 2000;
        if (years < 0 || years > 63) {
            throw new ValidationError(
                    "a build stamp holds the years 2000 to 2063", "at", Details.of("given", at.getYear()));
        }
        return ((long) at.getDayOfMonth() << 27)
                | ((long) at.getMonthValue() << 23)
                | ((long) years << 17)
                | ((long) at.getHour() << 12)
                | ((long) at.getMinute() << 6)
                | at.getSecond();
    }

    /** {@code 2026-01-15 02:00:00}. */
    public String describe() {
        return PRINTED.format(at);
    }
}
