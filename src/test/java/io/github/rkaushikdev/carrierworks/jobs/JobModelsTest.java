package io.github.rkaushikdev.carrierworks.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.ValidationError;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class JobModelsTest {

    private static final Instant NOW = Instant.parse("2025-09-29T11:00:00Z");
    private static final String DIGEST = "ab".repeat(32);

    private static ProgramJob.Builder job(String digest, JobMode mode) {
        return ProgramJob.builder("job-00001", "BX-0014", digest, mode, "Signal Processing", NOW);
    }

    private static ProgramJob job(JobMode mode) {
        return job(DIGEST, mode).build();
    }

    private static ProgramJob job() {
        return job(JobMode.JTAG);
    }

    @Test
    void aJobNormalisesItsNames() {
        ProgramJob live = job();
        assertThat(live.board()).isEqualTo("bx-14");
        assertThat(live.requestedBy()).isEqualTo("signal-processing");
        assertThat(live.state()).isEqualTo(JobState.QUEUED);
        assertThat(live.shortDigest()).isEqualTo(DIGEST.substring(0, 12));
    }

    @Test
    void theModesAreToldApart() {
        assertThat(job().writesFlash()).isFalse();
        assertThat(job(JobMode.FLASH_UPDATE).writesFlash()).isTrue();
        assertThat(job(JobMode.FLASH_UPDATE).touchesGolden()).isFalse();
        assertThat(job(JobMode.FLASH_GOLDEN).touchesGolden()).isTrue();
    }

    @Test
    void aModeNobodyHasIsRefused() {
        assertThatThrownBy(() -> job(JobMode.parse("serial"))).isInstanceOf(ValidationError.class);
    }

    @Test
    void aShortDigestIsRefusedBecauseTheStoreNeedsAWholeOne() {
        assertThatThrownBy(() -> job("abcdef", JobMode.JTAG).build()).isInstanceOf(ValidationError.class);
    }

    /**
     * A creation time with no zone is refused. A job keeps its moments as {@link Instant}s, which
     * are always UTC, so a zoneless time cannot be handed in; the test holds the type to that.
     */
    @Test
    void jobTimesAreInUtc() {
        assertThat(Arrays.stream(ProgramJob.class.getRecordComponents())
                        .filter(component -> component.getName().equals("createdAt"))
                        .<Object>map(component -> component.getType()))
                .containsExactly(Instant.class);
    }

    @Test
    void startingAJobCountsTheAttempt() {
        ProgramJob started = job().start(NOW.plusSeconds(5), "lease-00001");
        assertThat(started.state()).isEqualTo(JobState.RUNNING);
        assertThat(started.attempts()).isEqualTo(1);
        assertThat(started.startedAt()).contains(NOW.plusSeconds(5));
        assertThat(started.lease()).isEqualTo("lease-00001");
    }

    @Test
    void aJobCannotBeStartedTwice() {
        ProgramJob started = job().start(NOW);
        assertThatThrownBy(() -> started.start(NOW.plusSeconds(1))).isInstanceOf(ValidationError.class);
    }

    @Test
    void aPassingJobKeepsItsFirstStartTime() {
        ProgramJob started = job().start(NOW);
        ProgramJob done = started.passed(NOW.plus(Duration.ofMinutes(2)), 48_000);
        assertThat(done.succeeded()).isTrue();
        assertThat(done.finished()).isTrue();
        assertThat(done.clocks()).isEqualTo(48_000);
        assertThat(done.duration()).contains(Duration.ofMinutes(2));
    }

    @Test
    void aJobThatHasNotRunHasNoDuration() {
        assertThat(job().duration()).isEmpty();
        assertThat(job().start(NOW).duration()).isEmpty();
    }

    @Test
    void aRetryableFailurePutsTheJobBackOnTheQueue() {
        ProgramJob started = job().start(NOW);
        ProgramJob again = started.failed(NOW.plusSeconds(30), "chain read as all ones", "chain", true);
        assertThat(again.state()).isEqualTo(JobState.QUEUED);
        assertThat(again.finishedAt()).isEmpty();
        assertThat(again.attempts()).isEqualTo(1);
        assertThat(again.errorCode()).isEqualTo("chain");
    }

    @Test
    void aPermanentFailureEndsTheJob() {
        ProgramJob started = job().start(NOW);
        ProgramJob dead = started.failed(NOW.plusSeconds(30), "image is for another device");
        assertThat(dead.state()).isEqualTo(JobState.FAILED);
        assertThat(dead.finished()).isTrue();
        assertThat(dead.succeeded()).isFalse();
        assertThat(dead.finishedAt()).contains(NOW.plusSeconds(30));
    }

    @Test
    void aSecondAttemptCountsUp() {
        ProgramJob live = job().start(NOW);
        live = live.failed(NOW.plusSeconds(10), "cable", "", true);
        live = live.start(NOW.plusSeconds(20));
        assertThat(live.attempts()).isEqualTo(2);
        assertThat(live.startedAt()).contains(NOW);
    }

    @Test
    void attemptsRunOut() {
        ProgramJob live = job().start(NOW);
        assertThat(live.exhausted(3)).isFalse();
        live = live.failed(NOW, "cable", "", true).start(NOW);
        live = live.failed(NOW, "cable", "", true).start(NOW);
        assertThat(live.exhausted(3)).isTrue();
    }

    @Test
    void aFailureHasToSayWhy() {
        assertThatThrownBy(() -> job().start(NOW).failed(NOW, "   ")).isInstanceOf(ValidationError.class);
    }

    @Test
    void onlyARunningJobCanPassOrFail() {
        assertThatThrownBy(() -> job().passed(NOW)).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> job().failed(NOW, "nope")).isInstanceOf(ValidationError.class);
    }

    @Test
    void aQueuedJobCanBeCancelled() {
        ProgramJob cancelled = job().cancel(NOW.plus(Duration.ofMinutes(1)), "board went away");
        assertThat(cancelled.state()).isEqualTo(JobState.CANCELLED);
        assertThat(cancelled.finished()).isTrue();
        assertThat(cancelled.error()).isEqualTo("board went away");
    }

    @Test
    void aFinishedJobCannotBeCancelledOrRestarted() {
        ProgramJob done = job().start(NOW).passed(NOW.plus(Duration.ofMinutes(1)));
        assertThatThrownBy(() -> done.cancel(NOW.plus(Duration.ofMinutes(2)))).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> done.start(NOW.plus(Duration.ofMinutes(2)))).isInstanceOf(ValidationError.class);
    }

    @Test
    void aJobDescribesItselfWithItsError() {
        ProgramJob live = job().start(NOW).failed(NOW, "image is for another device");
        assertThat(live.describe())
                .isEqualTo("job-00001 jtag abababababab on bx-14 [failed] image is for another device");
        assertThat(job().describe()).isEqualTo("job-00001 jtag abababababab on bx-14 [queued]");
    }
}
