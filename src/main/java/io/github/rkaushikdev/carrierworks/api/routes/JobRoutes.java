package io.github.rkaushikdev.carrierworks.api.routes;

import io.github.rkaushikdev.carrierworks.Role;
import io.github.rkaushikdev.carrierworks.api.JobIn;
import io.github.rkaushikdev.carrierworks.api.JobOut;
import io.github.rkaushikdev.carrierworks.api.Requires;
import io.github.rkaushikdev.carrierworks.api.Work;
import io.github.rkaushikdev.carrierworks.jobs.JobMode;
import io.github.rkaushikdev.carrierworks.jobs.JobState;
import io.github.rkaushikdev.carrierworks.jobs.ProgramJob;
import io.github.rkaushikdev.carrierworks.services.Programming;
import io.github.rkaushikdev.carrierworks.util.Times;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The job routes: what has been asked for and what is next to run. */
@RestController
public class JobRoutes {

    private final Work work;
    private final Clock clock;

    public JobRoutes(Work work, Clock clock) {
        this.work = work;
        this.clock = clock;
    }

    /** Jobs for one image, or in one state: queued, running, passed, failed or cancelled. */
    @GetMapping("/jobs")
    @Requires(Role.READER)
    public List<JobOut> listJobs(@RequestParam Optional<String> state, @RequestParam Optional<String> digest) {
        return work.apply(unit -> {
            List<ProgramJob> found;
            if (digest.isPresent()) {
                found = unit.jobs().forDigest(digest.get());
            } else if (state.filter(JobState.RUNNING.value()::equals).isPresent()) {
                found = unit.jobs().running();
            } else {
                found = unit.boards().all().stream()
                        .flatMap(board -> unit.jobs().forBoard(board.tag()).stream())
                        .filter(job -> state.isEmpty() || job.state().value().equals(state.get()))
                        .toList();
            }
            return found.stream().map(JobOut::of).toList();
        });
    }

    /** The jobs a worker could start right now, oldest first. */
    @GetMapping("/jobs/ready")
    @Requires(Role.OPERATOR)
    public List<JobOut> listReady() {
        Instant moment = Times.now(clock);
        return work.apply(
                unit -> Programming.ready(unit, moment).stream().map(JobOut::of).toList());
    }

    /** One job. */
    @GetMapping("/jobs/{jobId}")
    @Requires(Role.READER)
    public JobOut readJob(@PathVariable String jobId) {
        return work.apply(unit -> JobOut.of(unit.jobs().get(jobId)));
    }

    /** Ask for a board to be programmed. */
    @PostMapping("/jobs")
    @ResponseStatus(HttpStatus.CREATED)
    @Requires(Role.OPERATOR)
    public JobOut submitJob(@Valid @RequestBody JobIn asked) {
        Instant moment = Times.now(clock);
        JobMode mode = JobMode.exactly(asked.mode());
        return work.apply(unit -> JobOut.of(Programming.submit(
                unit, asked.board(), asked.digest(), mode, asked.requestedBy(), moment, asked.verify())));
    }

    /** Take a job off the queue. */
    @PostMapping("/jobs/{jobId}/cancel")
    @Requires(Role.OPERATOR)
    public JobOut cancelJob(
            @PathVariable String jobId, @RequestParam(defaultValue = Programming.CANCELLED) String reason) {
        Instant moment = Times.now(clock);
        return work.apply(unit -> JobOut.of(Programming.cancel(unit, jobId, moment, reason)));
    }

    /** Everything that has been asked of one board. */
    @GetMapping("/boards/{tag}/jobs")
    @Requires(Role.READER)
    public List<JobOut> boardJobs(@PathVariable String tag) {
        return work.apply(unit -> {
            unit.boards().get(tag);
            return Programming.forBoard(unit, tag).stream().map(JobOut::of).toList();
        });
    }
}
