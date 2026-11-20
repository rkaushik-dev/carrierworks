package io.github.rkaushikdev.carrierworks.artifacts;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.Ids;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.util.Json;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Where an image came from: one build, as the build system described it, recorded at the moment it
 * arrives.
 *
 * <p>Provenance is separate from the artifact record because it is about the build rather than
 * about the bytes. Two builds can produce the same image from different sources, which sounds
 * impossible until somebody rebuilds a tag on a different agent and gets a byte identical result,
 * and the bench then has one artifact with two provenances. That is a fact worth keeping rather than
 * a conflict to resolve.
 *
 * <p>The constraints digest is the field people forget and the one that explains the most. A design
 * that closes timing on one build and not on the next, from the same source revision, has almost
 * always had its constraints changed by somebody who did not think of them as source.
 *
 * @param digest the digest of the image the build produced
 * @param design the design name, normalised
 * @param sourceRevision the git object name the build started from, lower case
 * @param buildId the build system's name for the build
 * @param builtAt when it ran, as the build system wrote it
 * @param toolVersion the toolchain that ran it, or empty
 * @param constraintsDigest a digest of the constraint files, or empty when nobody recorded one
 * @param builder the agent that ran it, or empty
 * @param branch the branch it was built from, as a slug, or empty
 * @param dirty whether the tree had uncommitted changes in it
 */
public record Provenance(
        String digest,
        String design,
        String sourceRevision,
        String buildId,
        String builtAt,
        String toolVersion,
        String constraintsDigest,
        String builder,
        String branch,
        boolean dirty) {

    private static final Pattern SHA = Pattern.compile("[0-9a-f]{7,40}");
    private static final Set<String> FIELDS = Set.of(
            "digest",
            "design",
            "source_revision",
            "build_id",
            "built_at",
            "tool_version",
            "constraints_digest",
            "builder",
            "branch",
            "dirty");

    public Provenance {
        Objects.requireNonNull(digest, "digest");
        design = Ids.designName(design);
        sourceRevision = sourceRevision.strip().toLowerCase(Locale.ROOT);
        if (!SHA.matcher(sourceRevision).matches()) {
            throw new ValidationError(
                    "a source revision is a git object name", "source_revision", Details.of("given", sourceRevision));
        }
        if (buildId.strip().isEmpty()) {
            throw new ValidationError("a build has an identifier", "build_id");
        }
        if (builtAt.strip().isEmpty()) {
            throw new ValidationError("a build has a time", "built_at");
        }
        Objects.requireNonNull(toolVersion, "toolVersion");
        Objects.requireNonNull(constraintsDigest, "constraintsDigest");
        Objects.requireNonNull(builder, "builder");
        if (!branch.isEmpty()) {
            branch = Ids.slug(branch);
        }
    }

    /** A clean build with nothing recorded beyond the facts every build has. */
    public Provenance(String digest, String design, String sourceRevision, String buildId, String builtAt) {
        this(digest, design, sourceRevision, buildId, builtAt, "", "", "", "", false);
    }

    /** Read a build back from the JSON {@link #toJson} wrote. */
    public static Provenance fromJson(String text) {
        return fromParsed(Json.parse(text));
    }

    /** A build out of one parsed JSON object, as {@link #asMap} lays it out. */
    static Provenance fromParsed(Object parsed) {
        JsonFields fields = JsonFields.of("a build record", parsed, FIELDS);
        return new Provenance(
                fields.text("digest"),
                fields.text("design"),
                fields.text("source_revision"),
                fields.text("build_id"),
                fields.text("built_at"),
                fields.text("tool_version", ""),
                fields.text("constraints_digest", ""),
                fields.text("builder", ""),
                fields.text("branch", ""),
                fields.flag("dirty", false));
    }

    /** The first twelve characters of the revision, which is how people quote one. */
    public String shortRevision() {
        return sourceRevision.substring(0, Math.min(12, sourceRevision.length()));
    }

    /** A build off a clean tree with its constraints recorded can be redone. */
    public boolean reproducible() {
        return !dirty && !constraintsDigest.isEmpty();
    }

    /**
     * Whether two builds started from the same inputs.
     *
     * <p>Revision and constraints both, because either one changing changes the result and only one
     * of them is in version control at most sites.
     */
    public boolean sameSourceAs(Provenance other) {
        return sourceRevision.equals(other.sourceRevision) && constraintsDigest.equals(other.constraintsDigest);
    }

    /** {@code radar_frontend from 9f2c11a4b7d3 on main (clean) build nightly-1841}. */
    public String describe() {
        String state = dirty ? "dirty" : "clean";
        String where = branch.isEmpty() ? "" : " on " + branch;
        return design + " from " + shortRevision() + where + " (" + state + ") build " + buildId;
    }

    /** The fields under the names they are stored with, in declaration order. */
    public Map<String, Object> asMap() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("digest", digest);
        fields.put("design", design);
        fields.put("source_revision", sourceRevision);
        fields.put("build_id", buildId);
        fields.put("built_at", builtAt);
        fields.put("tool_version", toolVersion);
        fields.put("constraints_digest", constraintsDigest);
        fields.put("builder", builder);
        fields.put("branch", branch);
        fields.put("dirty", dirty);
        return fields;
    }

    /** The build as JSON, keys sorted, two spaces to a level. */
    public String toJson() {
        return Json.writer().indent(2).sortKeys().write(asMap());
    }

    /** The same build with some fields changed. */
    public Builder toBuilder() {
        return new Builder(digest, design, sourceRevision, buildId, builtAt)
                .toolVersion(toolVersion)
                .constraintsDigest(constraintsDigest)
                .builder(builder)
                .branch(branch)
                .dirty(dirty);
    }

    /** A build described a field at a time, everything beyond the five required facts optional. */
    public static final class Builder {

        private String digest;
        private String design;
        private String sourceRevision;
        private String buildId;
        private String builtAt;
        private String toolVersion = "";
        private String constraintsDigest = "";
        private String builder = "";
        private String branch = "";
        private boolean dirty;

        /** A build with the facts every build has. */
        public Builder(String digest, String design, String sourceRevision, String buildId, String builtAt) {
            this.digest = digest;
            this.design = design;
            this.sourceRevision = sourceRevision;
            this.buildId = buildId;
            this.builtAt = builtAt;
        }

        public Builder digest(String value) {
            digest = value;
            return this;
        }

        public Builder design(String value) {
            design = value;
            return this;
        }

        public Builder sourceRevision(String value) {
            sourceRevision = value;
            return this;
        }

        public Builder buildId(String value) {
            buildId = value;
            return this;
        }

        public Builder builtAt(String value) {
            builtAt = value;
            return this;
        }

        public Builder toolVersion(String value) {
            toolVersion = value;
            return this;
        }

        public Builder constraintsDigest(String value) {
            constraintsDigest = value;
            return this;
        }

        /** The agent that ran the build. */
        public Builder builder(String value) {
            builder = value;
            return this;
        }

        public Builder branch(String value) {
            branch = value;
            return this;
        }

        public Builder dirty(boolean value) {
            dirty = value;
            return this;
        }

        /** The build, checked and normalised. */
        public Provenance build() {
            return new Provenance(
                    digest,
                    design,
                    sourceRevision,
                    buildId,
                    builtAt,
                    toolVersion,
                    constraintsDigest,
                    builder,
                    branch,
                    dirty);
        }
    }
}
