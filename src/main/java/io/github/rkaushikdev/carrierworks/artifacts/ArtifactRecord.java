package io.github.rkaushikdev.carrierworks.artifacts;

import io.github.rkaushikdev.carrierworks.Ids;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.bitstream.Bitstream;
import io.github.rkaushikdev.carrierworks.util.Json;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * What the bench knows about one stored image.
 *
 * @param digest the SHA-256 of the configuration data, as 64 lower case hex digits
 * @param design the design name, normalised
 * @param device the device code the image is for
 * @param length how many bytes of configuration data there are
 * @param kind whether it is a full or a partial image
 * @param channel the channel it is in, as a slug
 * @param source where it was read from, or empty
 * @param toolVersion the toolchain that built it, or empty
 * @param storedAt when it was stored, as ISO text to the second, or empty
 * @param tags the tags on it, as slugs, sorted and without repeats
 */
public record ArtifactRecord(
        String digest,
        String design,
        String device,
        long length,
        ArtifactKind kind,
        String channel,
        String source,
        String toolVersion,
        String storedAt,
        List<String> tags) {

    /** The channel a new image lands in. */
    public static final String DEFAULT_CHANNEL = "dev";

    private static final Set<String> FIELDS = Set.of(
            "digest", "design", "device", "length", "kind", "channel", "source", "tool_version", "stored_at", "tags");

    public ArtifactRecord {
        if (digest.length() != 64 || !digest.chars().allMatch(ArtifactRecord::isHexDigit)) {
            throw new ValidationError("a digest is sixty four hex characters", "digest");
        }
        design = Ids.designName(design);
        Objects.requireNonNull(device, "device");
        channel = Ids.slug(channel);
        TreeSet<String> slugs = new TreeSet<>();
        tags.forEach(tag -> slugs.add(Ids.slug(tag)));
        tags = List.copyOf(slugs);
        Objects.requireNonNull(kind, "kind");
        if (length <= 0) {
            throw new ValidationError("an artifact has some bytes", "length");
        }
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(toolVersion, "toolVersion");
        Objects.requireNonNull(storedAt, "storedAt");
    }

    /** A record in the dev channel with nothing known beyond the image itself. */
    public ArtifactRecord(String digest, String design, String device, long length, ArtifactKind kind) {
        this(digest, design, device, length, kind, DEFAULT_CHANNEL, "", "", "", List.of());
    }

    /** The record for an image that has not been stored yet, in the dev channel. */
    public static ArtifactRecord of(Bitstream image) {
        return of(image, DEFAULT_CHANNEL, "");
    }

    /** The record for an image, in a channel and stored at a moment given as ISO text. */
    public static ArtifactRecord of(Bitstream image, String channel, String storedAt) {
        return new ArtifactRecord(
                image.digest(),
                image.design(),
                image.device(),
                image.length(),
                image.isPartial() ? ArtifactKind.PARTIAL : ArtifactKind.FULL,
                channel,
                image.source(),
                image.header().toolVersion().orElse(""),
                storedAt,
                List.of());
    }

    /** Read a record back from the JSON {@link #toJson} wrote. */
    public static ArtifactRecord fromJson(String text) {
        JsonFields fields = JsonFields.of("an artifact record", Json.parse(text), FIELDS);
        return new ArtifactRecord(
                fields.text("digest"),
                fields.text("design"),
                fields.text("device"),
                fields.whole("length"),
                ArtifactKind.parse(fields.text("kind")),
                fields.text("channel", DEFAULT_CHANNEL),
                fields.text("source", ""),
                fields.text("tool_version", ""),
                fields.text("stored_at", ""),
                fields.texts("tags"));
    }

    /** The first twelve digits of the digest, which is how people quote an image. */
    public String shortDigest() {
        return digest.substring(0, 12);
    }

    /** Whether the image only rewrites part of a running device. */
    public boolean isPartial() {
        return kind == ArtifactKind.PARTIAL;
    }

    /** The same record in another channel. */
    public ArtifactRecord withChannel(String value) {
        return new ArtifactRecord(digest, design, device, length, kind, value, source, toolVersion, storedAt, tags);
    }

    /** The same record in another of the promotion channels. */
    public ArtifactRecord withChannel(Channel value) {
        return withChannel(value.value());
    }

    /** The same record with more tags on it. */
    public ArtifactRecord tagged(String... more) {
        List<String> all = new ArrayList<>(tags);
        all.addAll(Arrays.asList(more));
        return new ArtifactRecord(digest, design, device, length, kind, channel, source, toolVersion, storedAt, all);
    }

    /** {@code radar_frontend 1f2e3d4c5b6a full xc7a100t [dev]}. */
    public String describe() {
        return design + " " + shortDigest() + " " + kind + " " + device + " [" + channel + "]";
    }

    /** The fields under the names they are stored with, in declaration order. */
    public Map<String, Object> asMap() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("digest", digest);
        fields.put("design", design);
        fields.put("device", device);
        fields.put("length", length);
        fields.put("kind", kind.value());
        fields.put("channel", channel);
        fields.put("source", source);
        fields.put("tool_version", toolVersion);
        fields.put("stored_at", storedAt);
        fields.put("tags", tags);
        return fields;
    }

    /** The record as JSON, keys sorted, two spaces to a level. */
    public String toJson() {
        return Json.writer().indent(2).sortKeys().write(asMap());
    }

    private static boolean isHexDigit(int ch) {
        return (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f');
    }
}
