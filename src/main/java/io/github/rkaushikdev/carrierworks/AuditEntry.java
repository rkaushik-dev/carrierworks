package io.github.rkaushikdev.carrierworks;

import io.github.rkaushikdev.carrierworks.util.Times;
import java.time.Instant;

/**
 * One thing somebody did, and nothing that knows where it is stored.
 *
 * <p>The trail is deliberately thin prose rather than a structured event, because the question it
 * answers is always read by a person and never by a program. Who took the board, when, and what
 * they said it was for.
 *
 * @param at when, in UTC
 * @param actor who, as a slug
 * @param action what, as a slug
 * @param subject what it was done to, or empty
 * @param detail anything they said about it, or empty
 * @param keyId the key they used, or empty
 */
public record AuditEntry(Instant at, String actor, String action, String subject, String detail, String keyId) {

    public AuditEntry {
        actor = Ids.slug(actor);
        action = Ids.slug(action);
    }

    /** Somebody did something to nothing in particular. */
    public AuditEntry(Instant at, String actor, String action) {
        this(at, actor, action, "", "", "");
    }

    /** {@code 2026-01-24T09:00:00+00:00 ops lease-take bx-14: capture}. */
    public String describe() {
        String line = Times.iso(at, Times.Precision.SECONDS) + " " + actor + " " + action;
        if (!subject.isEmpty()) {
            line = line + " " + subject;
        }
        return detail.isEmpty() ? line : line + ": " + detail;
    }
}
