package io.github.rkaushikdev.carrierworks.scheduling;

import io.github.rkaushikdev.carrierworks.IdFactory;
import io.github.rkaushikdev.carrierworks.Ids;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.RequirementSet;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The waiting list, and the two rules that decide who is at the front of it.
 *
 * <p>Priority first, because some things genuinely matter more: a release verification run beats
 * somebody trying an experiment. Then fair share, which is the rule that stops the nightly
 * regression from holding every board on the bench while three people wait behind it. Among
 * requests of equal priority, the team currently holding fewest boards goes first, and time waiting
 * breaks the remaining tie.
 *
 * <p>The queue does not know what a board is. It orders requests and hands them out one at a time;
 * whether anything can satisfy the one at the front is the scheduler's problem, and a request that
 * cannot be satisfied right now stays where it is rather than being pushed to the back.
 *
 * <p>Holdings, wherever a method takes them, are how many boards each holder has now, keyed by
 * holder however the name was written.
 */
public final class RequestQueue {

    private final Map<String, Request> requests = new TreeMap<>();
    private final IdFactory ids;

    /** A queue issuing {@code req-00001} onwards. */
    public RequestQueue() {
        this(1);
    }

    /** A queue whose ids carry on from {@code start}, so a restart does not replay ids that exist. */
    public RequestQueue(long start) {
        this.ids = new IdFactory("req", start);
    }

    /** {@link #submit(String, String, RequirementSet, Instant, Request.Terms)} on the standard terms. */
    public Request submit(String holder, String purpose, RequirementSet requirements, Instant now) {
        return submit(holder, purpose, requirements, now, Request.Terms.standard());
    }

    /**
     * Put a request on the queue.
     *
     * @throws ValidationError when the purpose is blank or the terms do not hold up
     */
    public Request submit(
            String holder, String purpose, RequirementSet requirements, Instant now, Request.Terms terms) {
        Request request = new Request(ids.next(), holder, purpose, requirements, now, terms);
        requests.put(request.id(), request);
        return request;
    }

    /** One request by id, open or closed, or a not found. */
    public Request request(String requestId) {
        String key = Ids.slug(requestId);
        Request found = requests.get(key);
        if (found == null) {
            throw new NotFound("request", key);
        }
        return found;
    }

    /**
     * Withdraw a request and keep the closed form.
     *
     * @throws ValidationError when it is already closed
     */
    public Request cancel(String requestId, Instant now) {
        Request cancelled = request(requestId).cancel(now);
        requests.put(cancelled.id(), cancelled);
        return cancelled;
    }

    /**
     * Mark a request as having got its board.
     *
     * @throws ValidationError when it is already closed
     */
    public Request satisfy(String requestId, Instant now) {
        Request done = request(requestId).satisfy(now);
        requests.put(done.id(), done);
        return done;
    }

    /** Every request, open and closed, in id order. */
    public List<Request> all() {
        return List.copyOf(requests.values());
    }

    /** Every request still waiting, in id order. */
    public List<Request> pending() {
        return requests.values().stream().filter(Request::isOpen).toList();
    }

    /** {@link #ordered(Map)} with nobody holding anything. */
    public List<Request> ordered() {
        return ordered(Map.of());
    }

    /**
     * The queue in the order it should be served: by priority, then by how many boards the holder
     * has, then by when the request was submitted, then by id.
     */
    public List<Request> ordered(Map<String, Integer> holdings) {
        Map<String, Integer> held = new HashMap<>();
        holdings.forEach((holder, count) -> held.put(Ids.slug(holder), count));
        Comparator<Request> order = Comparator.comparingInt(Request::priority)
                .thenComparingInt(request -> held.getOrDefault(request.holder(), 0))
                .thenComparing(Request::submittedAt)
                .thenComparing(Request::id);
        return pending().stream().sorted(order).toList();
    }

    /** {@link #head(Map)} with nobody holding anything. */
    public Optional<Request> head() {
        return head(Map.of());
    }

    /** The request to serve next, if anything is waiting. */
    public Optional<Request> head(Map<String, Integer> holdings) {
        return ordered(holdings).stream().findFirst();
    }

    /** {@link #positionOf(String, Map)} with nobody holding anything. */
    public int positionOf(String requestId) {
        return positionOf(requestId, Map.of());
    }

    /**
     * Where a request sits in the queue, counted from one.
     *
     * <p>Zero means it is not waiting any more, which is a different answer from being at the front
     * and worth not confusing with it.
     *
     * @throws NotFound when nobody submitted that request
     */
    public int positionOf(String requestId, Map<String, Integer> holdings) {
        Request wanted = request(requestId);
        if (!wanted.isOpen()) {
            return 0;
        }
        return ordered(holdings).stream().map(Request::id).toList().indexOf(wanted.id()) + 1;
    }

    /** What one holder is still waiting on, in id order. */
    public List<Request> waitingFor(String holder) {
        String wanted = Ids.slug(holder);
        return pending().stream()
                .filter(request -> request.holder().equals(wanted))
                .toList();
    }

    /** The longest any waiting request has waited, or nothing when nothing is waiting. */
    public Duration longestWait(Instant now) {
        return pending().stream()
                .map(request -> request.waited(now))
                .max(Comparator.naturalOrder())
                .orElse(Duration.ZERO);
    }

    /** How many requests have been submitted, open and closed. */
    public int size() {
        return requests.size();
    }
}
