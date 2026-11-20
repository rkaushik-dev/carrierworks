package io.github.rkaushikdev.carrierworks.persistence;

import io.github.rkaushikdev.carrierworks.ApiKey;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.Role;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Credentials, stored as digests. */
public final class ApiKeyRepository {

    private final EntityManager session;

    ApiKeyRepository(EntityManager session) {
        this.session = session;
    }

    /** The session this repository reads and writes through. */
    public EntityManager session() {
        return session;
    }

    /** Record a newly issued key by the digest of its secret. */
    public ApiKey add(ApiKey key, String secretDigest, Instant now) {
        ApiKeyRow row = new ApiKeyRow();
        row.setId(key.id());
        row.setLabel(key.label());
        row.setSecretDigest(secretDigest);
        row.setRole(key.role().value());
        row.setTeam(key.team());
        row.setCreatedAt(now);
        row.setActive(key.active());
        session.persist(row);
        return key;
    }

    /** The key whose secret has this digest, if there is one. */
    public Optional<ApiKey> byDigest(String secretDigest) {
        return session.createQuery("select k from ApiKeyRow k where k.secretDigest = :digest", ApiKeyRow.class)
                .setParameter("digest", secretDigest)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .map(ApiKeyRepository::key);
    }

    /** One key, or a not found. */
    public ApiKey get(String keyId) {
        return key(row(keyId));
    }

    /** Record that a key was used, which is how a stale one is spotted. */
    public void touch(String keyId, Instant now) {
        ApiKeyRow row = session.find(ApiKeyRow.class, keyId);
        if (row != null) {
            row.setLastUsedAt(now);
        }
    }

    /** Revoke a key, handing back what it now is. */
    public ApiKey deactivate(String keyId) {
        ApiKeyRow row = row(keyId);
        row.setActive(false);
        return key(row);
    }

    /** Every key, oldest first. */
    public List<ApiKey> all() {
        return session
                .createQuery("select k from ApiKeyRow k order by k.createdAt", ApiKeyRow.class)
                .getResultList()
                .stream()
                .map(ApiKeyRepository::key)
                .toList();
    }

    private ApiKeyRow row(String keyId) {
        ApiKeyRow row = session.find(ApiKeyRow.class, keyId);
        if (row == null) {
            throw new NotFound("api key", keyId);
        }
        return row;
    }

    private static ApiKey key(ApiKeyRow row) {
        return new ApiKey(row.getId(), row.getLabel(), Role.parse(row.getRole()), row.getTeam(), row.isActive());
    }
}
