package io.github.rkaushikdev.carrierworks.api.routes;

import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.Carrierworks;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Whether the service is up, and the settings in force. */
@RestController
public class BenchRoutes {

    private final BenchSettings settings;

    public BenchRoutes(BenchSettings settings) {
        this.settings = settings;
    }

    /** Whether the service is up and which site it thinks it is. Touches nothing. */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("site", settings.site());
        body.put("version", Carrierworks.VERSION);
        return body;
    }

    /** The settings in force, with nothing secret in them to redact. */
    @GetMapping("/settings")
    public Map<String, Object> settings() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("site", settings.site());
        body.put("default_lease_minutes", settings.defaultLeaseMinutes());
        body.put("max_lease_minutes", settings.maxLeaseMinutes());
        body.put("program_attempts", settings.programAttempts());
        body.put("verify_after_program", settings.verifyAfterProgram());
        body.put("require_timing_closure", settings.requireTimingClosure());
        body.put("staffed_hours", settings.staffedHours());
        return body;
    }
}
