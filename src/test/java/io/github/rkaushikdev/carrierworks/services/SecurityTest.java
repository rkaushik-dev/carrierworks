package io.github.rkaushikdev.carrierworks.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.carrierworks.Access;
import io.github.rkaushikdev.carrierworks.ApiKey;
import io.github.rkaushikdev.carrierworks.Forbidden;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.Role;
import io.github.rkaushikdev.carrierworks.Unauthorized;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SecurityTest {

    private static final Instant NOW = Instant.parse("2026-01-24T09:00:00Z");

    private BenchDatabase database;

    @BeforeEach
    void open() {
        database = BenchDatabase.inMemory();
    }

    @AfterEach
    void close() {
        database.close();
    }

    private Security.Issued issue(String label, Role role) {
        return database.apply(unit -> Security.issue(unit, label, role, NOW));
    }

    @Test
    void theRolesRunFromReadingToChangingThings() {
        assertThat(Role.values()).extracting(Role::value).containsExactly("reader", "operator", "admin");
    }

    @Test
    void aRoleCoversEverythingBelowIt() {
        ApiKey admin = new ApiKey("1", "ops", Role.ADMIN);
        assertThat(admin.allows(Role.READER)).isTrue();
        assertThat(admin.allows(Role.OPERATOR)).isTrue();
        assertThat(admin.allows(Role.ADMIN)).isTrue();
    }

    @Test
    void aReaderCannotDoAnOperatorSWork() {
        ApiKey reader = new ApiKey("1", "dashboard", Role.READER);
        assertThat(reader.allows(Role.READER)).isTrue();
        assertThat(reader.allows(Role.OPERATOR)).isFalse();
    }

    @Test
    void aRevokedKeyAllowsNothing() {
        ApiKey dead = new ApiKey("1", "old", Role.ADMIN, "", false);
        assertThat(dead.allows(Role.READER)).isFalse();
    }

    /**
     * Roles are a closed set, so a role nobody has is refused where its name is read: neither a key
     * holding one nor a question about one can be put together.
     */
    @Test
    void aRoleNobodyHasIsABug() {
        assertThatThrownBy(() -> new ApiKey("1", "x", Role.parse("wizard"))).isInstanceOf(ValidationError.class);
        ApiKey reader = new ApiKey("1", "x", Role.READER);
        assertThatThrownBy(() -> reader.allows(Role.parse("wizard"))).isInstanceOf(ValidationError.class);
    }

    @Test
    void aKeyWithoutALabelIsRefused() {
        assertThatThrownBy(() -> new ApiKey("1", "  ", Role.READER)).isInstanceOf(ValidationError.class);
    }

    @Test
    void requiringARoleSaysWhatWasNeeded() {
        ApiKey reader = new ApiKey("1", "dashboard", Role.READER);
        Forbidden caught = catchThrowableOfType(Forbidden.class, () -> Access.requireRole(reader, Role.ADMIN));
        assertThat(caught.details()).isEqualTo(Map.of("needs", "admin", "holds", "reader"));
        assertThat(Access.requireRole(reader, Role.READER)).isSameAs(reader);
    }

    @Test
    void aSecretHashesToADigest() {
        assertThat(Access.hashSecret("hunter2")).hasSize(64);
        assertThat(Access.hashSecret("hunter2")).isEqualTo(Access.hashSecret(" hunter2 "));
        assertThat(Access.hashSecret("hunter2")).isNotEqualTo(Access.hashSecret("hunter3"));
    }

    @Test
    void anEmptySecretIsRefused() {
        assertThatThrownBy(() -> Access.hashSecret("   ")).isInstanceOf(ValidationError.class);
    }

    @Test
    void issuingAKeyHandsTheSecretBackOnce() {
        Security.Issued issued = issue("nightly runner", Role.OPERATOR);
        assertThat(issued.key().role()).isEqualTo(Role.OPERATOR);
        assertThat(issued.secret().length()).isGreaterThan(20);
        List<ApiKey> stored = database.apply(Security::keys);
        assertThat(stored).extracting(ApiKey::label).containsExactly("nightly runner");
    }

    @Test
    void theSecretIsNotStoredAnywhere() {
        String secret = issue("nightly runner", Role.OPERATOR).secret();
        List<ApiKey> rows = database.apply(Security::keys);
        assertThat(rows.toString()).doesNotContain(secret);
    }

    @Test
    void aKeyAuthenticatesWithItsSecret() {
        Security.Issued issued = issue("nightly runner", Role.OPERATOR);
        ApiKey found = database.apply(unit -> Security.authenticate(unit, issued.secret(), NOW));
        assertThat(found.id()).isEqualTo(issued.key().id());
        assertThat(found.role()).isEqualTo(Role.OPERATOR);
    }

    @Test
    void aSecretNobodyIssuedIsRefused() {
        issue("nightly runner", Role.OPERATOR);
        assertThatThrownBy(() -> database.run(unit -> Security.authenticate(unit, "not-a-key", NOW)))
                .isInstanceOf(Unauthorized.class);
    }

    @Test
    void noKeyAtAllIsRefused() {
        assertThatThrownBy(() -> database.run(unit -> Security.authenticate(unit, Optional.empty(), NOW)))
                .isInstanceOf(Unauthorized.class);
        assertThatThrownBy(() -> database.run(unit -> Security.authenticate(unit, "   ", NOW)))
                .isInstanceOf(Unauthorized.class);
    }

    @Test
    void aRevokedKeyIsRefusedWithADifferentError() {
        Security.Issued issued = issue("old laptop", Role.ADMIN);
        database.run(unit -> Security.revoke(unit, issued.key().id()));
        assertThatThrownBy(() -> database.run(unit -> Security.authenticate(unit, issued.secret(), NOW)))
                .isInstanceOf(Forbidden.class);
    }

    @Test
    void revokingAKeyKeepsTheRecordOfIt() {
        Security.Issued issued = issue("old laptop", Role.ADMIN);
        database.run(unit -> Security.revoke(unit, issued.key().id()));
        List<ApiKey> held = database.apply(Security::keys);
        assertThat(held).extracting(ApiKey::active).containsExactly(false);
        assertThat(held.getFirst().describe()).isEqualTo("old laptop [admin] (revoked)");
    }

    @Test
    void revokingAKeyNobodyIssuedIsANotFound() {
        assertThatThrownBy(() -> database.run(unit -> Security.revoke(unit, "nothing")))
                .isInstanceOf(NotFound.class);
    }

    @Test
    void usingAKeyRecordsWhen() {
        Security.Issued issued = issue("nightly runner", Role.OPERATOR);
        Instant later = NOW.plus(Duration.ofHours(2));
        database.run(unit -> Security.authenticate(unit, issued.secret(), later));
        ApiKey row = database.apply(unit -> unit.apiKeys().get(issued.key().id()));
        assertThat(row.active()).isTrue();
    }

    @Test
    void twoKeysCanShareARoleButNotASecret() {
        database.run(unit -> {
            Security.issue(unit, "runner one", Role.OPERATOR, NOW, "", "alpha");
            Security.issue(unit, "runner two", Role.OPERATOR, NOW, "", "beta");
        });
        database.run(unit -> {
            assertThat(Security.keys(unit)).hasSize(2);
            assertThat(Security.authenticate(unit, "beta", NOW).label()).isEqualTo("runner two");
        });
    }
}
