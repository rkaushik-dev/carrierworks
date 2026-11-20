package io.github.rkaushikdev.carrierworks.jobs;

import io.github.rkaushikdev.carrierworks.Ids;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.util.Json;
import io.github.rkaushikdev.carrierworks.util.Repr;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * One line of the job journal.
 *
 * <p>A line is one JSON object with its keys sorted: the time to the millisecond with an explicit
 * {@code +00:00}, the job, the event, and the detail when there is any, for example {@code {"at":
 * "2025-10-16T09:00:00.000+00:00", "detail": {"board": "bx-14"}, "event": "started", "job":
 * "job-00001"}}. Detail values have to be JSON values themselves: text, numbers, flags, null, lists
 * and maps of those.
 *
 * @param at when it happened
 * @param job the job, as a slug
 * @param event what happened
 * @param detail anything else worth writing down, in the order it was given
 */
public record JournalEntry(Instant at, String job, JournalEvent event, Map<String, Object> detail) {

    private static final Json.Writer LINE = Json.writer().sortKeys();

    public JournalEntry {
        job = Ids.slug(job);
        detail = Collections.unmodifiableMap(new LinkedHashMap<>(detail));
    }

    /** An entry with nothing beside the event. */
    public JournalEntry(Instant at, String job, JournalEvent event) {
        this(at, job, event, Map.of());
    }

    /** Whether this line says the job is over. */
    public boolean closes() {
        return event.closes();
    }

    /** The line as it is written to the file, without its newline; no detail leaves the key out. */
    public String toLine() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("at", Times.iso(at, Times.Precision.MILLISECONDS));
        payload.put("job", job);
        payload.put("event", event.value());
        if (!detail.isEmpty()) {
            payload.put("detail", detail);
        }
        return LINE.write(payload);
    }

    /**
     * Read one line back.
     *
     * <p>A line needs a time and a job. A missing event reads as a step and a missing detail as
     * none. A time with no offset is refused, because journal times are UTC.
     *
     * @throws ValidationError when the line is not JSON, not an object, lacks a time or a job, has
     *     fields of the wrong kind, or names an event nobody has
     * @throws DateTimeParseException when the time is not a timestamp at all
     */
    public static JournalEntry fromLine(String line) {
        Object raw;
        try {
            raw = Json.parse(line);
        } catch (Json.MalformedJsonException unreadable) {
            ValidationError refusal = new ValidationError("that is not a journal line", "line");
            refusal.initCause(unreadable);
            throw refusal;
        }
        if (!(raw instanceof Map<?, ?> fields) || !fields.containsKey("at") || !fields.containsKey("job")) {
            throw new ValidationError("a journal line has a time and a job", "line");
        }
        Object event = fields.containsKey("event") ? fields.get("event") : JournalEvent.STEP.value();
        Object detail = fields.get("detail");
        if (!(fields.get("at") instanceof String at)
                || !(fields.get("job") instanceof String job)
                || !(event instanceof String name)
                || (detail != null && !(detail instanceof Map<?, ?>))) {
            throw new ValidationError("that is not a journal line", "line");
        }
        return new JournalEntry(time(at), job, JournalEvent.parse(name), detail(detail));
    }

    /** {@code 2025-10-16T09:00:00+00:00 job-00001 step step=erase}, detail sorted by name. */
    public String describe() {
        String line = Times.iso(at, Times.Precision.SECONDS) + " " + job + " " + event.value();
        if (detail.isEmpty()) {
            return line;
        }
        String rendered = detail.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + text(entry.getValue()))
                .collect(Collectors.joining(" "));
        return line + " " + rendered;
    }

    /** A detail value as it reads in a sentence: text as itself, anything else in its quoted form. */
    static String text(Object value) {
        return value instanceof String text ? text : Repr.of(value);
    }

    private static Instant time(String text) {
        try {
            return Times.parse(text);
        } catch (DateTimeParseException unreadable) {
            if (zoneless(text)) {
                throw new ValidationError("journal times are in utc", "at");
            }
            throw unreadable;
        }
    }

    private static boolean zoneless(String text) {
        String cleaned = text.strip().replace(' ', 'T');
        try {
            LocalDateTime.parse(cleaned);
            return true;
        } catch (DateTimeParseException notADateAndTime) {
            try {
                LocalDate.parse(cleaned);
                return true;
            } catch (DateTimeParseException notADate) {
                return false;
            }
        }
    }

    private static Map<String, Object> detail(Object raw) {
        Map<String, Object> detail = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> fields) {
            fields.forEach((key, value) -> detail.put(key.toString(), value));
        }
        return detail;
    }
}
