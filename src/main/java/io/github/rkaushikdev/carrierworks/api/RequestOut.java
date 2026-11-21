package io.github.rkaushikdev.carrierworks.api;

import io.github.rkaushikdev.carrierworks.Requirement;
import io.github.rkaushikdev.carrierworks.scheduling.Request;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;

/**
 * A request on the way out, with where it stands.
 *
 * @param id the request id, such as {@code req-0001}
 * @param holder who asked
 * @param purpose what for
 * @param needs what the board has to do, in the words it was asked in
 * @param submittedAt when it was asked
 * @param priority how urgent, one being the most
 * @param minutes how long the board is wanted for
 * @param preemptible whether the lease may be taken away by something more urgent
 * @param needsHands whether the job needs somebody in the lab
 * @param state {@value #WAITING}, {@value #SERVED} or {@value #CANCELLED}
 * @param waitedSeconds how long it has waited, in whole seconds
 * @param place where it is in the line, counting from one, or null once it is closed
 * @param board the board it was given, or nothing until it has one
 */
public record RequestOut(
        String id,
        String holder,
        String purpose,
        List<String> needs,
        Instant submittedAt,
        int priority,
        int minutes,
        boolean preemptible,
        boolean needsHands,
        String state,
        long waitedSeconds,
        OptionalInt place,
        String board) {

    /** A request still in the line. */
    public static final String WAITING = "waiting";

    /** A request that was given a board. */
    public static final String SERVED = "served";

    /** A request somebody gave up. */
    public static final String CANCELLED = "cancelled";

    public RequestOut {
        needs = List.copyOf(needs);
    }

    /** A request at a moment, with no board and no place said. */
    public static RequestOut of(Request request, Instant moment) {
        return of(request, moment, "", OptionalInt.empty());
    }

    /** A request at a moment, with the board it was given and its place in the line. */
    public static RequestOut of(Request request, Instant moment, String board, OptionalInt place) {
        return new RequestOut(
                request.id(),
                request.holder(),
                request.purpose(),
                request.requirements().requirements().stream()
                        .map(Requirement::text)
                        .toList(),
                request.submittedAt(),
                request.priority(),
                request.minutes(),
                request.preemptible(),
                request.needsHands(),
                state(request),
                Times.seconds(request.waited(moment)),
                place,
                board);
    }

    private static String state(Request request) {
        if (request.satisfiedAt().isPresent()) {
            return SERVED;
        }
        if (request.cancelledAt().isPresent()) {
            return CANCELLED;
        }
        return WAITING;
    }
}
