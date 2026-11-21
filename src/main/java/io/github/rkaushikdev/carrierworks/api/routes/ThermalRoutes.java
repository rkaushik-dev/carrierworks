package io.github.rkaushikdev.carrierworks.api.routes;

import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.Role;
import io.github.rkaushikdev.carrierworks.api.Page;
import io.github.rkaushikdev.carrierworks.api.Requires;
import io.github.rkaushikdev.carrierworks.api.SampleIn;
import io.github.rkaushikdev.carrierworks.api.SampleOut;
import io.github.rkaushikdev.carrierworks.api.StatusOut;
import io.github.rkaushikdev.carrierworks.api.SupervisorActionOut;
import io.github.rkaushikdev.carrierworks.api.Work;
import io.github.rkaushikdev.carrierworks.services.Thermal;
import io.github.rkaushikdev.carrierworks.util.Times;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The thermal routes: readings in, states out, and the pass that acts.
 *
 * <p>Recording is the hot path on this service. Rack controllers post a reading per board every
 * twenty seconds, which is why the route takes a batch: one post per controller per poll instead of
 * one per board, and the whole batch lands in a single transaction or none of it does.
 *
 * <p>The tick is a post rather than a get because it changes things, and it is behind the operator
 * role for the same reason. Everything else here is a read.
 */
@RestController
public class ThermalRoutes {

    /** The most readings one batch may hold. */
    public static final int MOST_PER_BATCH = 256;

    /** The widest window of readings a status or a tick looks at, a day of minutes. */
    public static final int WIDEST_WINDOW = 1440;

    /** The furthest back a board's history goes, a week of minutes. */
    public static final int LONGEST_HISTORY = 10080;

    private final Work work;
    private final BenchSettings settings;
    private final Clock clock;

    public ThermalRoutes(Work work, BenchSettings settings, Clock clock) {
        this.work = work;
        this.settings = settings;
        this.clock = clock;
    }

    /** Store a batch of readings, all or nothing. A reading with no time is stamped as it arrives. */
    @PostMapping("/thermal/samples")
    @ResponseStatus(HttpStatus.CREATED)
    @Requires(Role.OPERATOR)
    public List<SampleOut> recordSamples(
            @RequestBody @Size(min = 1, max = MOST_PER_BATCH) List<@Valid SampleIn> readings) {
        Instant moment = Times.now(clock);
        List<Thermal.Reading> batch = readings.stream()
                .map(reading -> new Thermal.Reading(
                        reading.board(), reading.at().orElse(moment), reading.millidegrees(), reading.source()))
                .toList();
        return work.apply(unit ->
                Thermal.recordBatch(unit, batch).stream().map(SampleOut::of).toList());
    }

    /**
     * Where every board stands, worst first.
     *
     * @param window how many minutes of readings to look at
     */
    @GetMapping("/thermal/status")
    @Requires(Role.READER)
    public List<StatusOut> readStatus(@RequestParam(defaultValue = "15") @Min(1) @Max(WIDEST_WINDOW) int window) {
        Instant moment = Times.now(clock);
        return work.apply(unit -> Thermal.statusOf(unit, moment, settings, Duration.ofMinutes(window)).stream()
                .map(status -> StatusOut.of(status, moment))
                .toList());
    }

    /**
     * One board's readings, oldest first.
     *
     * @param window how many minutes back to go
     */
    @GetMapping("/boards/{tag}/thermal")
    @Requires(Role.READER)
    public List<SampleOut> readBoardHistory(
            @PathVariable String tag,
            @RequestParam(defaultValue = "60") @Min(1) @Max(LONGEST_HISTORY) int window,
            Page rows) {
        Instant moment = Times.now(clock);
        return work.apply(unit -> {
            unit.boards().get(tag);
            return rows.slice(unit.thermal().since(moment.minus(Duration.ofMinutes(window)), tag)).stream()
                    .map(SampleOut::of)
                    .toList();
        });
    }

    /**
     * One supervisor pass, which can take a board out of service.
     *
     * @param window how many minutes of readings to look at
     */
    @PostMapping("/thermal/tick")
    @Requires(Role.OPERATOR)
    public List<SupervisorActionOut> runTick(
            @RequestParam(defaultValue = "15") @Min(1) @Max(WIDEST_WINDOW) int window) {
        Instant moment = Times.now(clock);
        return work.apply(unit -> Thermal.tick(unit, moment, settings, Duration.ofMinutes(window)).stream()
                .map(SupervisorActionOut::of)
                .toList());
    }
}
