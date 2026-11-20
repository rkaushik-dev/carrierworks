package io.github.rkaushikdev.carrierworks.services;

import io.github.rkaushikdev.carrierworks.Access;
import io.github.rkaushikdev.carrierworks.ApiKey;
import io.github.rkaushikdev.carrierworks.Forbidden;
import io.github.rkaushikdev.carrierworks.Role;
import io.github.rkaushikdev.carrierworks.Unauthorized;
import io.github.rkaushikdev.carrierworks.persistence.UnitOfWork;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Issuing keys, checking them, and turning them off.
 *
 * <p>The rules about roles live in {@link Access}, where nothing needs a database to reason about
 * them. What is here is the part that does need one: finding the key a secret belongs to, recording
 * that it was used, and revoking it without losing the record that it existed.
 */
public final class Security {

    /** Random bytes in a generated secret, which is 32 characters once encoded. */
    private static final int SECRET_BYTES = 24;

    /** Random bytes in a key id, which is 16 hex digits. */
    private static final int ID_BYTES = 8;

    private static final SecureRandom RANDOM = new SecureRandom();

    private Security() {}

    /**
     * A key that has just been issued, with its secret, which is never seen again.
     *
     * @param key the key as it is stored
     * @param secret the secret to hand to whoever asked for it
     */
    public record Issued(ApiKey key, String secret) {

        /** The key, with the secret left out so it cannot end up in a log line. */
        @Override
        public String toString() {
            return "Issued[key=" + key + "]";
        }
    }

    /** {@link #issue(UnitOfWork, String, Role, Instant, String, String)} for no team, with a fresh secret. */
    public static Issued issue(UnitOfWork unit, String label, Role role, Instant now) {
        return issue(unit, label, role, now, "", "");
    }

    /** {@link #issue(UnitOfWork, String, Role, Instant, String, String)} with a fresh secret. */
    public static Issued issue(UnitOfWork unit, String label, Role role, Instant now, String team) {
        return issue(unit, label, role, now, team, "");
    }

    /**
     * Make a key, handing back the secret exactly once.
     *
     * <p>The secret is returned rather than stored, and this is the only moment it exists anywhere
     * the caller can see. Losing it means issuing another key.
     *
     * @param secret the secret to issue the key with, or empty to have a random one made
     */
    public static Issued issue(UnitOfWork unit, String label, Role role, Instant now, String team, String secret) {
        String plain = secret.isEmpty() ? token(SECRET_BYTES) : secret;
        ApiKey key = new ApiKey(HexFormat.of().formatHex(bytes(ID_BYTES)), label, role, team, true);
        unit.apiKeys().add(key, Access.hashSecret(plain), now);
        return new Issued(key, plain);
    }

    /** {@link #authenticate(UnitOfWork, Optional, Instant)} for a secret that was presented. */
    public static ApiKey authenticate(UnitOfWork unit, String presented, Instant now) {
        return authenticate(unit, Optional.of(presented), now);
    }

    /**
     * Turn a presented secret into a key, refusing anything that is not one, and record its use.
     *
     * @param presented the secret as it arrived, or empty when nothing was sent
     * @throws Unauthorized when no secret was presented, or it belongs to no key
     * @throws Forbidden when it belongs to a key that has been revoked
     */
    public static ApiKey authenticate(UnitOfWork unit, Optional<String> presented, Instant now) {
        if (presented.isEmpty() || presented.get().isBlank()) {
            throw new Unauthorized("no key was presented");
        }
        ApiKey key = unit.apiKeys()
                .byDigest(Access.hashSecret(presented.get()))
                .orElseThrow(() -> new Unauthorized("that key is not one of ours"));
        if (!key.active()) {
            throw new Forbidden("that key has been revoked", "", key.role().value());
        }
        unit.apiKeys().touch(key.id(), now);
        return key;
    }

    /**
     * Turn a key off without deleting the record of it having existed.
     *
     * @throws io.github.rkaushikdev.carrierworks.NotFound when no key has that id
     */
    public static ApiKey revoke(UnitOfWork unit, String keyId) {
        return unit.apiKeys().deactivate(keyId);
    }

    /** Every key ever issued, live or revoked, oldest first. */
    public static List<ApiKey> keys(UnitOfWork unit) {
        return unit.apiKeys().all();
    }

    private static String token(int size) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(size));
    }

    private static byte[] bytes(int size) {
        byte[] random = new byte[size];
        RANDOM.nextBytes(random);
        return random;
    }
}
