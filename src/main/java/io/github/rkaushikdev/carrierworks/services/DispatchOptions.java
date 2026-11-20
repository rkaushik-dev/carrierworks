package io.github.rkaushikdev.carrierworks.services;

import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.scheduling.PreemptionPolicy;
import java.util.Map;
import java.util.Objects;

/**
 * How one pass over the waiting list is allowed to hand boards out.
 *
 * <p>The standard pass runs on the default settings, gives every holder as many boards as it can,
 * hands out as many as it can, and never takes a board off anybody.
 *
 * @param settings the settings each lease is checked against
 * @param quotas the most boards each holder may hold at once, by holder; a holder not named has no
 *     quota
 * @param limit the most requests the pass serves, or zero for no limit
 * @param preempt whether a request nothing free will do for may take a board off a lease
 * @param policy what may be taken when preempting
 */
public record DispatchOptions(
        BenchSettings settings, Map<String, Integer> quotas, int limit, boolean preempt, PreemptionPolicy policy) {

    public DispatchOptions {
        Objects.requireNonNull(settings, "settings");
        quotas = Map.copyOf(quotas);
        Objects.requireNonNull(policy, "policy");
    }

    /** The default settings, no quotas, no limit, and no preemption. */
    public static DispatchOptions standard() {
        return new DispatchOptions(BenchSettings.defaults(), Map.of(), 0, false, new PreemptionPolicy());
    }

    public DispatchOptions withSettings(BenchSettings value) {
        return new DispatchOptions(value, quotas, limit, preempt, policy);
    }

    public DispatchOptions withQuotas(Map<String, Integer> value) {
        return new DispatchOptions(settings, value, limit, preempt, policy);
    }

    public DispatchOptions withLimit(int value) {
        return new DispatchOptions(settings, quotas, value, preempt, policy);
    }

    public DispatchOptions withPreempt(boolean value) {
        return new DispatchOptions(settings, quotas, limit, value, policy);
    }

    public DispatchOptions withPolicy(PreemptionPolicy value) {
        return new DispatchOptions(settings, quotas, limit, preempt, value);
    }
}
