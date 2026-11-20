package io.github.rkaushikdev.carrierworks.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import org.hibernate.annotations.Check;

/**
 * One credential, stored as a digest rather than as a secret.
 *
 * <p>The secret is never written down. What is stored is a SHA-256 of it, so a copy of this table is not
 * a way into the bench, and a lost key is issued again rather than looked up.
 */
@Entity
@Table(
        name = "api_keys",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_api_keys_secret",
                    columnNames = {"secret_digest"})
        },
        indexes = {@Index(name = "ix_api_keys_role", columnList = "role")})
@Check(name = "ck_api_keys_digest", constraints = "length(secret_digest) = 64")
public class ApiKeyRow {

    @Id
    @Column(name = "id", nullable = false, length = 32)
    private String id;

    @Column(name = "label", nullable = false, length = 64)
    private String label;

    @Column(name = "secret_digest", nullable = false, length = 64)
    private String secretDigest;

    @Column(name = "role", nullable = false, length = 16)
    private String role;

    @Column(name = "team", nullable = false, length = 64)
    private String team = "";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    /** An empty row, filled in through its setters. */
    public ApiKeyRow() {}

    public String getId() {
        return id;
    }

    public void setId(String value) {
        id = value;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String value) {
        label = value;
    }

    public String getSecretDigest() {
        return secretDigest;
    }

    public void setSecretDigest(String value) {
        secretDigest = value;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String value) {
        role = value;
    }

    public String getTeam() {
        return team;
    }

    public void setTeam(String value) {
        team = value;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant value) {
        createdAt = value;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(Instant value) {
        lastUsedAt = value;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean value) {
        active = value;
    }
}
