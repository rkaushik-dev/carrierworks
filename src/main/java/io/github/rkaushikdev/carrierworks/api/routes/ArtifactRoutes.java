package io.github.rkaushikdev.carrierworks.api.routes;

import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.Role;
import io.github.rkaushikdev.carrierworks.api.Errors;
import io.github.rkaushikdev.carrierworks.api.Requires;
import io.github.rkaushikdev.carrierworks.api.Work;
import io.github.rkaushikdev.carrierworks.artifacts.ArtifactRecord;
import io.github.rkaushikdev.carrierworks.artifacts.ArtifactStore;
import io.github.rkaushikdev.carrierworks.artifacts.Channel;
import io.github.rkaushikdev.carrierworks.artifacts.PromotionDecision;
import io.github.rkaushikdev.carrierworks.artifacts.Provenance;
import io.github.rkaushikdev.carrierworks.bitstream.Bitstream;
import io.github.rkaushikdev.carrierworks.reports.LoadCounts;
import io.github.rkaushikdev.carrierworks.services.Registry;
import io.github.rkaushikdev.carrierworks.util.Times;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The artifact routes: what images the bench holds and which channel they are in.
 *
 * <p>The images themselves live in the store under the settings' artifact root, stamped by the
 * service's clock; their records live in the database.
 */
@RestController
@RequestMapping("/artifacts")
public class ArtifactRoutes {

    /** What an uploaded image is recorded as having come from. */
    public static final String UPLOAD = "upload";

    private final Work work;
    private final ArtifactStore store;
    private final Clock clock;

    public ArtifactRoutes(Work work, BenchSettings settings, Clock clock) {
        this.work = work;
        this.store = new ArtifactStore(settings.artifactRoot(), clock);
        this.clock = clock;
    }

    /**
     * An image's record on the way out.
     *
     * @param digest the SHA-256 of its configuration data
     * @param design the design it was built from
     * @param device the device it is for
     * @param length how many bytes of configuration data it holds
     * @param kind full or partial
     * @param channel the promotion channel it is in
     * @param toolVersion the tool that built it, as its header says
     * @param tags anything it has been tagged with
     * @param storedAt when it was stored
     */
    public record ArtifactOut(
            String digest,
            String design,
            String device,
            long length,
            String kind,
            String channel,
            String toolVersion,
            List<String> tags,
            String storedAt) {

        public ArtifactOut {
            tags = List.copyOf(tags);
        }

        /** One record. */
        public static ArtifactOut of(ArtifactRecord record) {
            return new ArtifactOut(
                    record.digest(),
                    record.design(),
                    record.device(),
                    record.length(),
                    record.kind().value(),
                    record.channel(),
                    record.toolVersion(),
                    record.tags(),
                    record.storedAt());
        }
    }

    /**
     * The build that produced an image, as a build job reports it.
     *
     * @param buildId the build's own id
     * @param sourceRevision the revision it was built from
     * @param builtAt when, as the build wrote it
     * @param constraintsDigest the digest of the constraints it used, nothing when left out
     * @param branch the branch it was built from, nothing when left out
     * @param builder the machine or person that built it, nothing when left out
     * @param dirty whether the tree had changes nobody committed, no when left out
     */
    public record BuildIn(
            @NotNull(message = Errors.REQUIRED) String buildId,
            @NotNull(message = Errors.REQUIRED) String sourceRevision,
            @NotNull(message = Errors.REQUIRED) String builtAt,
            String constraintsDigest,
            String branch,
            String builder,
            Boolean dirty) {

        public BuildIn {
            constraintsDigest = Objects.requireNonNullElse(constraintsDigest, "");
            branch = Objects.requireNonNullElse(branch, "");
            builder = Objects.requireNonNullElse(builder, "");
            dirty = Objects.requireNonNullElse(dirty, false);
        }
    }

    /**
     * A build that has been written down.
     *
     * @param digest the image it produced
     * @param buildId the build's own id
     * @param reproducible whether the build could be done again from what it recorded
     */
    public record BuildOut(String digest, String buildId, boolean reproducible) {}

    /**
     * A move to another channel being asked for.
     *
     * @param channel the channel to move to
     * @param approvals who has signed it off, nobody when left out
     * @param timingClosed what the caller says about timing, used only when no report was read
     */
    public record PromotionIn(
            @NotNull(message = Errors.REQUIRED) String channel,
            List<String> approvals,
            Optional<Boolean> timingClosed) {

        public PromotionIn {
            approvals = approvals == null ? List.of() : List.copyOf(approvals);
            timingClosed = Objects.requireNonNullElse(timingClosed, Optional.empty());
        }
    }

    /**
     * A move that was made.
     *
     * @param digest the image moved
     * @param channel the channel it is in now
     * @param moved whether it moved, which an answer rather than a refusal always says it did
     */
    public record PromotionOut(String digest, String channel, boolean moved) {}

    /**
     * How an image has done on the boards.
     *
     * @param passed how many loads of it passed
     * @param failed how many failed after reaching a board
     */
    public record LoadsOut(int passed, int failed) {

        /** The counts as the registry keeps them. */
        public static LoadsOut of(LoadCounts counts) {
            return new LoadsOut(counts.passed(), counts.failed());
        }
    }

    /** Images of one design, or in one channel, or every image in dev, qual and golden. */
    @GetMapping
    @Requires(Role.READER)
    public List<ArtifactOut> listArtifacts(
            @RequestParam Optional<String> design, @RequestParam Optional<String> channel) {
        return work.apply(unit -> {
            List<ArtifactRecord> found;
            if (design.isPresent()) {
                found = unit.artifacts().forDesign(design.get());
            } else if (channel.isPresent()) {
                found = unit.artifacts().inChannel(channel.get());
            } else {
                found = new ArrayList<>();
                for (Channel each : List.of(Channel.DEV, Channel.QUAL, Channel.GOLDEN)) {
                    found.addAll(unit.artifacts().inChannel(each.value()));
                }
            }
            return found.stream().map(ArtifactOut::of).toList();
        });
    }

    /** One image's record. */
    @GetMapping("/{digest}")
    @Requires(Role.READER)
    public ArtifactOut readArtifact(@PathVariable String digest) {
        return work.apply(unit -> ArtifactOut.of(unit.artifacts().get(digest)));
    }

    /** Take a bit file in, store it, and record what it is. The same image twice is one record. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Requires(Role.OPERATOR)
    public ArtifactOut uploadArtifact(
            @RequestBody byte[] body, @RequestParam(defaultValue = ArtifactRecord.DEFAULT_CHANNEL) String channel) {
        Instant moment = Times.now(clock);
        Bitstream image = Bitstream.read(body, UPLOAD);
        return work.apply(unit -> ArtifactOut.of(Registry.ingest(unit, store, image, moment, channel)));
    }

    /** Record the build that produced an image. */
    @PostMapping("/{digest}/builds")
    @ResponseStatus(HttpStatus.CREATED)
    @Requires(Role.OPERATOR)
    public BuildOut recordBuild(@PathVariable String digest, @Valid @RequestBody BuildIn reported) {
        return work.apply(unit -> {
            ArtifactRecord record = unit.artifacts().get(digest);
            Provenance build = new Provenance.Builder(
                            digest, record.design(), reported.sourceRevision(), reported.buildId(), reported.builtAt())
                    .constraintsDigest(reported.constraintsDigest())
                    .branch(reported.branch())
                    .builder(reported.builder())
                    .dirty(reported.dirty())
                    .build();
            Registry.recordBuild(unit, build);
            return new BuildOut(digest, build.buildId(), build.reproducible());
        });
    }

    /** Move an image up a channel, if everything the rules ask for is true. */
    @PostMapping("/{digest}/promote")
    @Requires(Role.ADMIN)
    public PromotionOut promoteArtifact(@PathVariable String digest, @Valid @RequestBody PromotionIn asked) {
        return work.apply(unit -> {
            PromotionDecision decision = Registry.promote(
                    unit,
                    digest,
                    asked.channel(),
                    Registry.evidenceFrom(unit, digest, asked.timingClosed(), asked.approvals()));
            return new PromotionOut(digest, decision.toChannel().value(), decision.allowed());
        });
    }

    /** How many times this image has been loaded, and how often that failed. */
    @GetMapping("/{digest}/loads")
    @Requires(Role.READER)
    public LoadsOut readLoads(@PathVariable String digest) {
        return work.apply(unit -> {
            unit.artifacts().get(digest);
            return LoadsOut.of(Registry.countedLoads(unit, digest));
        });
    }
}
