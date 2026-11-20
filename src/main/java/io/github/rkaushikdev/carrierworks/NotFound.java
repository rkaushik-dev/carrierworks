package io.github.rkaushikdev.carrierworks;

/** Asked for a thing the bench has never heard of. */
public class NotFound extends BenchError {

    private final String kind;
    private final String key;

    /** Nothing of this kind is known by this key. */
    public NotFound(String kind, String key) {
        super("no " + kind + " known as " + key, Details.of("kind", kind, "key", key));
        this.kind = kind;
        this.key = key;
    }

    /** What sort of thing was asked for. */
    public String kind() {
        return kind;
    }

    /** What it was asked for by. */
    public String key() {
        return key;
    }

    @Override
    public String code() {
        return "not_found";
    }
}
