package io.github.rkaushikdev.carrierworks.api.routes;

import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.Role;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.api.DispatchOut;
import io.github.rkaushikdev.carrierworks.api.Page;
import io.github.rkaushikdev.carrierworks.api.RequestIn;
import io.github.rkaushikdev.carrierworks.api.RequestOut;
import io.github.rkaushikdev.carrierworks.api.Requires;
import io.github.rkaushikdev.carrierworks.api.Work;
import io.github.rkaushikdev.carrierworks.persistence.UnitOfWork;
import io.github.rkaushikdev.carrierworks.scheduling.Request;
import io.github.rkaushikdev.carrierworks.services.DispatchOptions;
import io.github.rkaushikdev.carrierworks.services.Scheduling;
import io.github.rkaushikdev.carrierworks.util.Times;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The queue routes: ask for a board, see the line, run a pass over it.
 *
 * <p>The interesting one is the read of a single request, because that is what a build job polls
 * while it waits. It answers with the place in the line and the board once there is one, so a job
 * can block on a board without knowing anything about how the bench decides.
 *
 * <p>Dispatch is a post and it is behind the operator role. Everything a pass does is a lease
 * somebody else could have taken by hand, so it is not privileged work, but it is work that changes
 * who has what.
 */
@RestController
public class QueueRoutes {

    /** The most boards one pass may be asked to hand out. */
    public static final int MOST_PER_PASS = 64;

    private final Work work;
    private final BenchSettings settings;
    private final Clock clock;

    public QueueRoutes(Work work, BenchSettings settings, Clock clock) {
        this.work = work;
        this.settings = settings;
        this.clock = clock;
    }

    /** The line, in the order it will be served, or one holder's requests, or every request ever made. */
    @GetMapping("/requests")
    @Requires(Role.READER)
    public List<RequestOut> listRequests(
            @RequestParam Optional<String> holder, @RequestParam(defaultValue = "false") boolean closed, Page window) {
        Instant moment = Times.now(clock);
        return work.apply(unit -> {
            List<Request> found;
            if (holder.isPresent()) {
                found = unit.requests().forHolder(holder.get());
            } else if (closed) {
                found = unit.requests().all();
            } else {
                found = unit.requests().ordered(moment);
            }
            return window.slice(found).stream()
                    .map(entry ->
                            RequestOut.of(entry, moment, unit.requests().boardFor(entry.id()), OptionalInt.empty()))
                    .toList();
        });
    }

    /** Ask for a board and take a place in the line. */
    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    @Requires(Role.OPERATOR)
    public RequestOut submitRequest(@Valid @RequestBody RequestIn asked) {
        Instant moment = Times.now(clock);
        Request.Terms terms = Request.Terms.standard()
                .withPriority(asked.priority())
                .withMinutes(asked.minutes())
                .withPreemptible(asked.preemptible())
                .withNeedsHands(asked.needsHands());
        return work.apply(unit -> {
            Request request = Scheduling.submit(unit, asked.holder(), asked.purpose(), asked.needs(), moment, terms);
            return RequestOut.of(request, moment, "", place(unit, request.id(), moment));
        });
    }

    /** One request, with its place in the line and its board once it has one. */
    @GetMapping("/requests/{requestId}")
    @Requires(Role.READER)
    public RequestOut readRequest(@PathVariable String requestId) {
        Instant moment = Times.now(clock);
        return work.apply(unit -> RequestOut.of(
                unit.requests().get(requestId),
                moment,
                unit.requests().boardFor(requestId),
                place(unit, requestId, moment)));
    }

    /** Give up a place in the line. */
    @DeleteMapping("/requests/{requestId}")
    @Requires(Role.OPERATOR)
    public RequestOut cancelRequest(@PathVariable String requestId) {
        Instant moment = Times.now(clock);
        return work.apply(unit -> RequestOut.of(Scheduling.cancel(unit, requestId, moment), moment));
    }

    /**
     * One pass over the line, taking every board it can.
     *
     * @param quotas the most boards each holder may hold at once, by holder, as the whole body
     * @param limit hand out at most this many, or as many as it can when zero
     */
    @PostMapping("/requests/dispatch")
    @Requires(Role.OPERATOR)
    public DispatchOut dispatch(
            @RequestBody(required = false) Map<String, Integer> quotas,
            @RequestParam(defaultValue = "0") @Min(0) @Max(MOST_PER_PASS) int limit) {
        Instant moment = Times.now(clock);
        Map<String, Integer> given = quotas == null ? Map.of() : quotas;
        given.forEach((holder, most) -> {
            if (most == null) {
                throw new ValidationError(
                        "a quota is a whole number of boards", "quotas", Details.of("holder", holder));
            }
        });
        DispatchOptions options = DispatchOptions.standard()
                .withSettings(settings)
                .withQuotas(given)
                .withLimit(limit);
        return work.apply(unit -> DispatchOut.of(Scheduling.dispatch(unit, moment, options)));
    }

    /** Where a request sits in the line, counting from one, or nothing once it is closed. */
    private static OptionalInt place(UnitOfWork unit, String requestId, Instant moment) {
        List<Request> waiting = unit.requests().ordered(moment);
        for (int index = 0; index < waiting.size(); index++) {
            if (waiting.get(index).id().equals(requestId)) {
                return OptionalInt.of(index + 1);
            }
        }
        return OptionalInt.empty();
    }
}
