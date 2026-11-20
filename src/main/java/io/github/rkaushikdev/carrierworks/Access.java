package io.github.rkaushikdev.carrierworks;

import io.github.rkaushikdev.carrierworks.util.Digests;

/**
 * Who is allowed to do what, as three roles and one hash.
 *
 * <p>The secret is never kept. A key is issued once, its digest is stored, and a lost key is
 * replaced rather than recovered. That is not security theatre: this database gets copied onto
 * laptops for debugging, and a table of live credentials would go with it.
 */
public final class Access {

    private Access() {}

    /** The digest a key is stored as: SHA-256 of the trimmed secret, in lower case hex. */
    public static String hashSecret(String secret) {
        if (secret.isBlank()) {
            throw new ValidationError("a key has a secret", "secret");
        }
        return Digests.sha256(secret.strip());
    }

    /** Check a key carries a role, and say what it would have needed. */
    public static ApiKey requireRole(ApiKey key, Role needed) {
        if (!key.allows(needed)) {
            throw new Forbidden(
                    "that key is not allowed to do this",
                    needed.value(),
                    key.role().value());
        }
        return key;
    }
}
