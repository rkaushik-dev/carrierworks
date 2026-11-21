package io.github.rkaushikdev.carrierworks.testroutes;

import io.github.rkaushikdev.carrierworks.ChainError;
import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.NotFound;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Routes that fail on purpose, added to the service by the tests that need them. */
@RestController
public class FailingRoutes {

    @GetMapping("/boom")
    public Map<String, Object> boom() {
        throw new NotFound("board", "bx-99");
    }

    @GetMapping("/flaky")
    public Map<String, Object> flaky() {
        throw new ChainError("the chain read as all ones", Details.of("board", "bx-14"));
    }
}
