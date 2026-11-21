package io.github.rkaushikdev.carrierworks.api.routes;

import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.Role;
import io.github.rkaushikdev.carrierworks.api.LeaseIn;
import io.github.rkaushikdev.carrierworks.api.LeaseOut;
import io.github.rkaushikdev.carrierworks.api.Page;
import io.github.rkaushikdev.carrierworks.api.Requires;
import io.github.rkaushikdev.carrierworks.api.Work;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import io.github.rkaushikdev.carrierworks.services.LeaseTerms;
import io.github.rkaushikdev.carrierworks.services.Leasing;
import io.github.rkaushikdev.carrierworks.util.Times;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The lease routes: who has what, and for how much longer. */
@RestController
public class LeaseRoutes {

    private final Work work;
    private final BenchSettings settings;
    private final Clock clock;

    public LeaseRoutes(Work work, BenchSettings settings, Clock clock) {
        this.work = work;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * How much longer a lease is wanted for.
     *
     * @param minutes how many minutes to add, {@value #DEFAULT_MINUTES} when left out
     */
    public record Renewal(Integer minutes) {

        /** What a renewal adds when it does not say. */
        public static final int DEFAULT_MINUTES = 60;

        public Renewal {
            minutes = Objects.requireNonNullElse(minutes, DEFAULT_MINUTES);
        }
    }

    /** Every lease being held right now, or only one holder's. */
    @GetMapping("/leases")
    @Requires(Role.READER)
    public List<LeaseOut> listLeases(@RequestParam Optional<String> holder, Page window) {
        Instant moment = Times.now(clock);
        return work.apply(unit -> {
            List<Lease> held = holder.isPresent()
                    ? unit.leases().heldBy(holder.get(), moment)
                    : unit.leases().active(moment);
            return window.slice(held).stream().map(LeaseOut::of).toList();
        });
    }

    /** One lease, live or closed. */
    @GetMapping("/leases/{leaseId}")
    @Requires(Role.READER)
    public LeaseOut readLease(@PathVariable String leaseId) {
        return work.apply(unit -> LeaseOut.of(unit.leases().get(leaseId)));
    }

    /** Whatever holds a board, or {@code null} when it is free. */
    @GetMapping("/boards/{tag}/lease")
    @Requires(Role.READER)
    public Optional<LeaseOut> readBoardLease(@PathVariable String tag) {
        Instant moment = Times.now(clock);
        return work.apply(unit -> {
            unit.boards().get(tag);
            return unit.leases().forBoard(tag, moment).map(LeaseOut::of);
        });
    }

    /** Take a board, if it is free and in service. */
    @PostMapping("/boards/{tag}/lease")
    @ResponseStatus(HttpStatus.CREATED)
    @Requires(Role.OPERATOR)
    public LeaseOut takeBoard(@PathVariable String tag, @Valid @RequestBody LeaseIn asked) {
        Instant moment = Times.now(clock);
        LeaseTerms terms = LeaseTerms.standard()
                .withMinutes(asked.minutes().orElse(0))
                .withPriority(asked.priority())
                .withPreemptible(asked.preemptible());
        return work.apply(unit ->
                LeaseOut.of(Leasing.acquire(unit, tag, asked.holder(), asked.purpose(), moment, terms, settings)));
    }

    /** Give a board back. */
    @PostMapping("/leases/{leaseId}/release")
    @Requires(Role.OPERATOR)
    public LeaseOut releaseLease(
            @PathVariable String leaseId, @RequestParam(defaultValue = Lease.RELEASED) String reason) {
        Instant moment = Times.now(clock);
        return work.apply(unit -> LeaseOut.of(Leasing.release(unit, leaseId, moment, reason)));
    }

    /** Keep a board a bit longer, up to the ceiling. */
    @PostMapping("/leases/{leaseId}/renew")
    @Requires(Role.OPERATOR)
    public LeaseOut renewLease(@PathVariable String leaseId, @RequestBody(required = false) Renewal renewal) {
        Instant moment = Times.now(clock);
        int minutes = renewal == null ? Renewal.DEFAULT_MINUTES : renewal.minutes();
        return work.apply(unit -> LeaseOut.of(Leasing.renew(unit, leaseId, moment, minutes, settings)));
    }

    /** Close the leases that have run out and free their boards. */
    @PostMapping("/leases/sweep")
    public List<LeaseOut> sweep() {
        Instant moment = Times.now(clock);
        return work.apply(unit ->
                Leasing.sweepExpired(unit, moment).stream().map(LeaseOut::of).toList());
    }
}
