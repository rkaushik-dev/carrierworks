package io.github.rkaushikdev.carrierworks.api.routes;

import io.github.rkaushikdev.carrierworks.BenchError;
import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.Role;
import io.github.rkaushikdev.carrierworks.api.OverviewOut;
import io.github.rkaushikdev.carrierworks.api.Requires;
import io.github.rkaushikdev.carrierworks.api.Work;
import io.github.rkaushikdev.carrierworks.services.BenchOverview;
import io.github.rkaushikdev.carrierworks.services.Overview;
import io.github.rkaushikdev.carrierworks.util.Times;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The status routes: the roll up, and the readiness probe under it.
 *
 * <p>Two different questions get asked of a service like this and they have different answers.
 * Health is whether the process is up, which the service answers without touching anything.
 * Readiness is whether the bench behind it is usable, which needs the database and is the one that
 * should take a load balancer out of rotation.
 *
 * <p>The roll up itself is a read for anybody with a key, because it is the page on the wall in the
 * lab and everybody in the lab can see that wall.
 */
@RestController
public class OverviewRoutes {

    /** The furthest ahead a lease can be counted as expiring, a day of minutes. */
    public static final int WIDEST_WINDOW = 1440;

    private final Work work;
    private final BenchSettings settings;
    private final Clock clock;

    public OverviewRoutes(Work work, BenchSettings settings, Clock clock) {
        this.work = work;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * What the whole bench is doing right now.
     *
     * @param expiringWithin how many minutes ahead to count leases as expiring
     */
    @GetMapping("/status")
    @Requires(Role.READER)
    public OverviewOut readStatus(
            @RequestParam(name = "expiring_within", defaultValue = "15") @Min(1) @Max(WIDEST_WINDOW)
                    int expiringWithin) {
        Instant moment = Times.now(clock);
        return work.apply(
                unit -> OverviewOut.of(Overview.overview(unit, moment, settings, Duration.ofMinutes(expiringWithin))));
    }

    /**
     * Whether this instance can serve the bench, database and all.
     *
     * <p>Answers with a body either way rather than failing, because a probe that gets a five
     * hundred tells whoever is looking at it less than one that says which check failed. A bench
     * that cannot answer is a 503 carrying the reason and the bench's code for it.
     */
    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> readReady() {
        Instant moment = Times.now(clock);
        BenchOverview state;
        try {
            state = work.apply(unit -> Overview.overview(unit, moment, settings));
        } catch (BenchError failure) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ready", false);
            body.put("reason", failure.message());
            body.put("code", failure.code());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ready", true);
        body.put("site", state.site());
        body.put("boards", state.totalBoards());
        body.put("available", state.available());
        body.put("healthy", state.healthy());
        return ResponseEntity.ok(body);
    }
}
