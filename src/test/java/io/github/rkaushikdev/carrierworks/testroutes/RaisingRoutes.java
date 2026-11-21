package io.github.rkaushikdev.carrierworks.testroutes;

import io.github.rkaushikdev.carrierworks.BenchError;
import io.github.rkaushikdev.carrierworks.ChainError;
import io.github.rkaushikdev.carrierworks.ConfigurationError;
import io.github.rkaushikdev.carrierworks.Conflict;
import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.DeviceMismatch;
import io.github.rkaushikdev.carrierworks.Forbidden;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.PowerFault;
import io.github.rkaushikdev.carrierworks.TransientBenchError;
import io.github.rkaushikdev.carrierworks.Unauthorized;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** A route that raises one of every kind of bench error, named by the path. */
@RestController
public class RaisingRoutes {

    /** Every kind of error the route raises, by the name in its path. */
    public static final Map<String, Supplier<BenchError>> RAISERS = raisers();

    private static Map<String, Supplier<BenchError>> raisers() {
        Map<String, Supplier<BenchError>> raisers = new LinkedHashMap<>();
        raisers.put("transient", () -> new TransientBenchError("the adapter stopped answering"));
        raisers.put("chain", () -> new ChainError("the chain read as all ones"));
        raisers.put("power", () -> new PowerFault("the outlet did not switch"));
        raisers.put("conflict", () -> new Conflict("that board is busy"));
        raisers.put("not_found", () -> new NotFound("board", "bx-99"));
        raisers.put("validation", () -> new ValidationError("that is not a temperature", "millidegrees"));
        raisers.put("unauthorized", () -> new Unauthorized("no key"));
        raisers.put("forbidden", () -> new Forbidden("reader cannot do that"));
        raisers.put("configuration", () -> new ConfigurationError("no such setting", Details.of("setting", "nope")));
        raisers.put("mismatch", () -> new DeviceMismatch("that image is for another part", "xc7k325t", "xc7a100t"));
        return Collections.unmodifiableMap(raisers);
    }

    @GetMapping("/boom/{kind}")
    public Map<String, String> boom(@PathVariable String kind) {
        throw RAISERS.get(kind).get();
    }
}
