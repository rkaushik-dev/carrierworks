package io.github.rkaushikdev.carrierworks.scheduling;

import java.util.List;

/**
 * Whether a lease may be taken, and why not.
 *
 * @param lease the lease id
 * @param board the board it holds
 * @param blockers every rule that stops the lease being taken, in the order they were checked;
 *     empty when it may be
 * @param emergency whether it is being taken regardless of the rules
 */
public record PreemptionDecision(String lease, String board, List<String> blockers, boolean emergency) {

    public PreemptionDecision {
        blockers = List.copyOf(blockers);
    }

    /** Whether nothing stands in the way. */
    public boolean allowed() {
        return blockers.isEmpty();
    }

    /**
     * {@code lease-00001 on bx-14 may be taken (preemption)}, or {@code ... stays: } followed by
     * every blocker.
     */
    public String describe() {
        if (allowed()) {
            String kind = emergency ? "emergency" : "preemption";
            return lease + " on " + board + " may be taken (" + kind + ")";
        }
        return lease + " on " + board + " stays: " + String.join("; ", blockers);
    }
}
