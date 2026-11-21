package io.github.rkaushikdev.carrierworks.api.routes;

import io.github.rkaushikdev.carrierworks.ApiKey;
import io.github.rkaushikdev.carrierworks.Role;
import io.github.rkaushikdev.carrierworks.api.Errors;
import io.github.rkaushikdev.carrierworks.api.Requires;
import io.github.rkaushikdev.carrierworks.api.Work;
import io.github.rkaushikdev.carrierworks.services.Security;
import io.github.rkaushikdev.carrierworks.util.Times;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The key routes: issuing credentials and turning them off. */
@RestController
@RequestMapping("/keys")
public class KeyRoutes {

    private final Work work;
    private final Clock clock;

    public KeyRoutes(Work work, Clock clock) {
        this.work = work;
        this.clock = clock;
    }

    /**
     * A key being asked for.
     *
     * @param label what the key is for, such as {@code nightly runner}
     * @param role reader, operator or admin
     * @param team the team it belongs to, nothing when left out
     */
    public record KeyIn(
            @NotNull(message = Errors.REQUIRED) String label,
            @NotNull(message = Errors.REQUIRED) String role,
            String team) {

        public KeyIn {
            team = Objects.requireNonNullElse(team, "");
        }
    }

    /**
     * A key on the way out, never with its secret.
     *
     * @param id the key's id
     * @param label what it is for
     * @param role what it may do
     * @param team the team it belongs to, or nothing
     * @param active whether it still works
     */
    public record KeyOut(String id, String label, String role, String team, boolean active) {

        /** One key. */
        public static KeyOut of(ApiKey key) {
            return new KeyOut(key.id(), key.label(), key.role().value(), key.team(), key.active());
        }
    }

    /**
     * A key that has just been issued, with the secret that is never seen again.
     *
     * @param id the key's id
     * @param label what it is for
     * @param role what it may do
     * @param team the team it belongs to, or nothing
     * @param active whether it works, which a new key does
     * @param secret what to present in the key header from now on
     */
    public record IssuedOut(String id, String label, String role, String team, boolean active, String secret) {

        /** A key just issued, and its secret. */
        public static IssuedOut of(Security.Issued issued) {
            KeyOut key = KeyOut.of(issued.key());
            return new IssuedOut(key.id(), key.label(), key.role(), key.team(), key.active(), issued.secret());
        }

        /** Everything but the secret, so the secret cannot end up in a log line. */
        @Override
        public String toString() {
            return "IssuedOut[id=" + id + ", label=" + label + ", role=" + role + "]";
        }
    }

    /** Every key, live and revoked, without a secret among them. */
    @GetMapping
    @Requires(Role.ADMIN)
    public List<KeyOut> listKeys() {
        return work.apply(unit -> Security.keys(unit).stream().map(KeyOut::of).toList());
    }

    /** Issue a key. The secret comes back once and is never stored. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Requires(Role.ADMIN)
    public IssuedOut issueKey(@Valid @RequestBody KeyIn asked) {
        Instant moment = Times.now(clock);
        Role role = Role.parse(asked.role());
        return work.apply(unit -> IssuedOut.of(Security.issue(unit, asked.label(), role, moment, asked.team())));
    }

    /** Turn a key off, keeping the record that it existed. */
    @PostMapping("/{keyId}/revoke")
    @Requires(Role.ADMIN)
    public KeyOut revokeKey(@PathVariable String keyId) {
        return work.apply(unit -> KeyOut.of(Security.revoke(unit, keyId)));
    }

    /** Whoever this key is, which is how a runner checks its own role. */
    @GetMapping("/mine")
    @Requires(Role.READER)
    public KeyOut readMine(ApiKey key) {
        return KeyOut.of(key);
    }
}
