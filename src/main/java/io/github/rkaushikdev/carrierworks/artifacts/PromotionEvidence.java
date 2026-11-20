package io.github.rkaushikdev.carrierworks.artifacts;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything the bench has learned about an image since it arrived.
 *
 * @param timingClosed whether the build report says timing closed; empty when none has been read
 * @param worstSlackPs the worst setup slack in the report, in picoseconds, when there is one
 * @param successfulLoads how many loads of the image on the bench went cleanly
 * @param failedLoads how many failed on the board
 * @param approvals who has approved the image
 * @param provenance every build recorded as producing it
 * @param disagreements the builds whose inputs differ from the first one recorded
 */
public record PromotionEvidence(
        Optional<Boolean> timingClosed,
        Optional<Long> worstSlackPs,
        int successfulLoads,
        int failedLoads,
        List<String> approvals,
        List<Provenance> provenance,
        List<String> disagreements) {

    /** Nothing learned at all. */
    public static final PromotionEvidence NONE = builder().build();

    public PromotionEvidence {
        Objects.requireNonNull(timingClosed, "timingClosed");
        Objects.requireNonNull(worstSlackPs, "worstSlackPs");
        approvals = List.copyOf(approvals);
        provenance = List.copyOf(provenance);
        disagreements = List.copyOf(disagreements);
    }

    /** Evidence gathered a piece at a time, starting from nothing. */
    public static Builder builder() {
        return new Builder();
    }

    /** The same evidence with some of it changed. */
    public Builder toBuilder() {
        return new Builder()
                .timingClosed(timingClosed)
                .worstSlackPs(worstSlackPs)
                .successfulLoads(successfulLoads)
                .failedLoads(failedLoads)
                .approvals(approvals)
                .provenance(provenance)
                .disagreements(disagreements);
    }

    /** How many times the image has been loaded, either way. */
    public int loadAttempts() {
        return successfulLoads + failedLoads;
    }

    /** Every recorded build has to be reproducible, not just one of them, and there has to be one. */
    public boolean reproducible() {
        return !provenance.isEmpty() && provenance.stream().allMatch(Provenance::reproducible);
    }

    /** Promotion evidence, a piece at a time; anything not given is unknown or none. */
    public static final class Builder {

        private Optional<Boolean> timingClosed = Optional.empty();
        private Optional<Long> worstSlackPs = Optional.empty();
        private int successfulLoads;
        private int failedLoads;
        private List<String> approvals = List.of();
        private List<Provenance> provenance = List.of();
        private List<String> disagreements = List.of();

        private Builder() {}

        /** Whether timing closed, as a report said. */
        public Builder timingClosed(boolean value) {
            return timingClosed(Optional.of(value));
        }

        /** Whether timing closed, or empty when no report has been read. */
        public Builder timingClosed(Optional<Boolean> value) {
            timingClosed = value;
            return this;
        }

        /** The worst setup slack, in picoseconds. */
        public Builder worstSlackPs(long value) {
            return worstSlackPs(Optional.of(value));
        }

        /** The worst setup slack, or empty when no report has been read. */
        public Builder worstSlackPs(Optional<Long> value) {
            worstSlackPs = value;
            return this;
        }

        public Builder successfulLoads(int value) {
            successfulLoads = value;
            return this;
        }

        public Builder failedLoads(int value) {
            failedLoads = value;
            return this;
        }

        public Builder approvals(List<String> value) {
            approvals = value;
            return this;
        }

        public Builder approvals(String... value) {
            return approvals(Arrays.asList(value));
        }

        public Builder provenance(List<Provenance> value) {
            provenance = value;
            return this;
        }

        public Builder provenance(Provenance... value) {
            return provenance(Arrays.asList(value));
        }

        public Builder disagreements(List<String> value) {
            disagreements = value;
            return this;
        }

        public Builder disagreements(String... value) {
            return disagreements(Arrays.asList(value));
        }

        /** The evidence. */
        public PromotionEvidence build() {
            return new PromotionEvidence(
                    timingClosed, worstSlackPs, successfulLoads, failedLoads, approvals, provenance, disagreements);
        }
    }
}
