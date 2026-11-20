package io.github.rkaushikdev.carrierworks;

import io.github.rkaushikdev.carrierworks.util.Repr;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Base for everything the bench raises on purpose.
 *
 * <p>Two things are load bearing here and both exist because of how the bench gets used. First,
 * every error carries a stable {@link #code()}. The web layer puts it in a JSON envelope and the
 * operators grep the job logs for it, so renaming one is a breaking change even though nothing in
 * Java cares. Second, an error says whether the operation that raised it is worth trying again. A
 * programming job that fails because the cable fell out should come back around; one that fails
 * because the bitstream targets the wrong device never will, and retrying it just burns a slot on
 * the queue.
 *
 * <p>Anything that escapes without being one of these is a defect, and the job runner treats it
 * that way: an unknown exception fails the job and does not retry it.
 */
public class BenchError extends RuntimeException {

    private final String message;
    private final transient Map<String, Object> details;

    /** A refusal with nothing beside the sentence. */
    public BenchError(String message) {
        this(message, Map.of());
    }

    /** A refusal and the specifics behind it, in the order given. */
    public BenchError(String message, Map<String, ?> details) {
        super(message);
        this.message = message;
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    /** The same, with the exception underneath it kept as the cause. */
    public BenchError(String message, Map<String, ?> details, Throwable cause) {
        super(message, cause);
        this.message = message;
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    /** The stable code for this kind of refusal. */
    public String code() {
        return "bench_error";
    }

    /** Whether the operation that raised this is worth trying again. */
    public boolean retryable() {
        return false;
    }

    /** The sentence on its own, without the specifics. */
    public String message() {
        return message;
    }

    /** The specifics behind the sentence. Read only. */
    public Map<String, Object> details() {
        return details;
    }

    /**
     * The sentence followed by the specifics, sorted by name and quoted:
     * {@code bad shift (bits=17, tap='u2')}.
     */
    @Override
    public String getMessage() {
        if (details.isEmpty()) {
            return message;
        }
        String rendered = details.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + Repr.of(entry.getValue()))
                .collect(Collectors.joining(", "));
        return message + " (" + rendered + ")";
    }

    /**
     * The shape the http layer serialises and the job log records.
     *
     * <p>The maps are fresh copies, so a caller that edits the body cannot reach back into the
     * error.
     */
    public Map<String, Object> asMap() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code());
        body.put("message", message);
        body.put("retryable", retryable());
        body.put("details", new LinkedHashMap<>(details));
        return body;
    }

    /** Details with one more entry appended, for the subclasses that take a named field. */
    static Map<String, Object> plus(Map<String, ?> details, String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(details);
        merged.put(key, value);
        return merged;
    }
}
