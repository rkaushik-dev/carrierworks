package io.github.rkaushikdev.carrierworks.persistence;

import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.Requirement;
import io.github.rkaushikdev.carrierworks.RequirementSet;
import io.github.rkaushikdev.carrierworks.scheduling.Request;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/** The waiting list, ordered the way the dispatcher serves it. */
public final class RequestRepository {

    private final EntityManager session;

    RequestRepository(EntityManager session) {
        this.session = session;
    }

    /** The session this repository reads and writes through. */
    public EntityManager session() {
        return session;
    }

    /** The next request number, carried on from what is already stored: {@code req-0001}. */
    public String nextId() {
        long highest = 0;
        for (String id : session.createQuery("select r.id from RequestRow r", String.class)
                .getResultList()) {
            String tail = id.substring(id.lastIndexOf('-') + 1);
            if (!tail.isEmpty() && tail.chars().allMatch(Character::isDigit) && tail.length() < 18) {
                highest = Math.max(highest, Long.parseLong(tail));
            }
        }
        return String.format(Locale.ROOT, "req-%04d", highest + 1);
    }

    /** Put a request on the list, with the board it was given straight away if any. */
    public Request add(Request request) {
        return add(request, "");
    }

    /** Put a request on the list, recording the board it was given. */
    public Request add(Request request, String board) {
        RequestRow row = new RequestRow();
        row.setId(request.id());
        row.setHolder(request.holder());
        row.setPurpose(request.purpose());
        row.setRequirements(StreamSupport.stream(request.requirements().spliterator(), false)
                .map(Requirement::text)
                .collect(Collectors.joining(",")));
        row.setSubmittedAt(request.submittedAt());
        row.setPriority(request.priority());
        row.setMinutes(request.minutes());
        row.setPreemptible(request.preemptible());
        row.setNeedsHands(request.needsHands());
        row.setCancelledAt(request.cancelledAt().orElse(null));
        row.setSatisfiedAt(request.satisfiedAt().orElse(null));
        row.setAssignedBoard(board);
        session.persist(row);
        session.flush();
        return request;
    }

    /** One request, or a not found. */
    public Request get(String requestId) {
        return request(row(requestId));
    }

    /** Write what can change about a request, keeping the board it was given. */
    public Request save(Request request) {
        return save(request, Optional.empty());
    }

    /** Write what can change about a request, and the board it was given when there is one. */
    public Request save(Request request, Optional<String> board) {
        RequestRow row = row(request.id());
        row.setCancelledAt(request.cancelledAt().orElse(null));
        row.setSatisfiedAt(request.satisfiedAt().orElse(null));
        row.setPriority(request.priority());
        row.setMinutes(request.minutes());
        board.ifPresent(row::setAssignedBoard);
        session.flush();
        return request;
    }

    /** Which board a satisfied request got, or empty while it waits. */
    public String boardFor(String requestId) {
        return row(requestId).getAssignedBoard();
    }

    /** Every request, oldest first. */
    public List<Request> all() {
        return requests(session.createQuery("select r from RequestRow r order by r.submittedAt", RequestRow.class)
                .getResultList());
    }

    /** Every request still waiting, oldest first. */
    public List<Request> open() {
        return requests(session.createQuery(
                        "select r from RequestRow r where r.satisfiedAt is null and r.cancelledAt is null"
                                + " order by r.submittedAt",
                        RequestRow.class)
                .getResultList());
    }

    /** Every request one holder made, oldest first. */
    public List<Request> forHolder(String holder) {
        return requests(session.createQuery(
                        "select r from RequestRow r where r.holder = :holder order by r.submittedAt", RequestRow.class)
                .setParameter("holder", holder.strip().toLowerCase(Locale.ROOT))
                .getResultList());
    }

    /** Open requests, highest priority first and longest waiting next. */
    public List<Request> ordered(Instant now) {
        return open().stream()
                .sorted(Comparator.comparingInt(Request::priority)
                        .thenComparing(request -> request.waited(now), Comparator.reverseOrder())
                        .thenComparing(Request::submittedAt))
                .toList();
    }

    private RequestRow row(String requestId) {
        RequestRow row = session.find(RequestRow.class, requestId.strip().toLowerCase(Locale.ROOT));
        if (row == null) {
            throw new NotFound("request", requestId);
        }
        return row;
    }

    private static List<Request> requests(List<RequestRow> rows) {
        return rows.stream().map(RequestRepository::request).toList();
    }

    private static Request request(RequestRow row) {
        return new Request(
                row.getId(),
                row.getHolder(),
                row.getPurpose(),
                RequirementSet.parse(row.getRequirements()),
                row.getSubmittedAt(),
                row.getPriority(),
                row.getMinutes(),
                row.isPreemptible(),
                row.isNeedsHands(),
                Optional.ofNullable(row.getCancelledAt()),
                Optional.ofNullable(row.getSatisfiedAt()));
    }
}
