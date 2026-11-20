package io.github.rkaushikdev.carrierworks.services;

import io.github.rkaushikdev.carrierworks.ApiKey;
import io.github.rkaushikdev.carrierworks.AuditEntry;
import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.persistence.AuditRepository;
import io.github.rkaushikdev.carrierworks.persistence.UnitOfWork;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Writing down who did what, and reading it back when somebody asks.
 *
 * <p>Every write the bench performs on somebody's behalf gets a line: who asked, what they asked
 * for, and what it landed on. The trail is deliberately thin prose rather than a structured event,
 * because the question it answers is always read by a person and never by a program.
 *
 * <p>Nothing here fails the operation it is recording. An audit write that threw would turn a
 * successful lease into an error, and the bench would rather have a lease with no line about it
 * than a person unable to take a board because the logging is broken. The line is written in the
 * same unit of work, so it lands with the change or not at all, and that is the strongest guarantee
 * worth having.
 */
public final class Audit {

    /** Who a line names when nobody in particular asked for the change. */
    public static final String BENCH = "bench";

    /** How many lines a listing shows unless it is told otherwise. */
    public static final int DEFAULT_LIMIT = AuditRepository.DEFAULT_LIMIT;

    /** The longest detail a line keeps; anything longer is cut rather than refused. */
    static final int DETAIL_LIMIT = 240;

    private Audit() {}

    /** A line by the bench itself, with no detail. */
    public static AuditEntry record(UnitOfWork unit, Instant now, String action, String subject) {
        return record(unit, now, action, BENCH, subject, "");
    }

    /** Write one line, in the same unit of work as whatever it describes. */
    public static AuditEntry record(
            UnitOfWork unit, Instant now, String action, String actor, String subject, String detail) {
        return unit.audit().add(new AuditEntry(now, actor, action, subject, trimmed(detail), ""));
    }

    /** Write one line naming the key that asked for the change, by its label and its id. */
    public static AuditEntry record(
            UnitOfWork unit, Instant now, String action, ApiKey key, String subject, String detail) {
        return unit.audit().add(new AuditEntry(now, key.label(), action, subject, trimmed(detail), key.id()));
    }

    /** The last fifty lines written, newest first. */
    public static List<AuditEntry> recent(UnitOfWork unit) {
        return recent(unit, DEFAULT_LIMIT);
    }

    /**
     * The last lines written, newest first.
     *
     * @throws ValidationError when fewer than one line is asked for
     */
    public static List<AuditEntry> recent(UnitOfWork unit, int limit) {
        if (limit < 1) {
            throw new ValidationError("ask for at least one line", "limit", Details.of("given", limit));
        }
        return unit.audit().recent(limit);
    }

    /** The last fifty lines about one board, image or lease, newest first. */
    public static List<AuditEntry> forSubject(UnitOfWork unit, String subject) {
        return forSubject(unit, subject, DEFAULT_LIMIT);
    }

    /** Everything done to one board, image or lease, newest first. */
    public static List<AuditEntry> forSubject(UnitOfWork unit, String subject, int limit) {
        return unit.audit().forSubject(subject, limit);
    }

    /**
     * The trail over a window ending now, oldest first, which is what the daily report reads.
     *
     * @throws ValidationError when the window has no length
     */
    public static List<AuditEntry> since(UnitOfWork unit, Instant now, Duration window) {
        if (!window.isPositive()) {
            throw new ValidationError("a window has some length", "window");
        }
        return unit.audit().since(now.minus(window));
    }

    /** The trail, narrowed by whatever the caller knows, newest first. */
    public static List<AuditEntry> search(UnitOfWork unit, AuditRepository.Search search) {
        return unit.audit().search(search);
    }

    /** A detail cut to the length a line keeps, counting characters rather than encoded units. */
    static String trimmed(String detail) {
        if (detail.codePointCount(0, detail.length()) <= DETAIL_LIMIT) {
            return detail;
        }
        return detail.substring(0, detail.offsetByCodePoints(0, DETAIL_LIMIT));
    }
}
