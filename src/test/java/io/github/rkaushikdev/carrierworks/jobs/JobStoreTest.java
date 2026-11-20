package io.github.rkaushikdev.carrierworks.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class JobStoreTest {

    private static final Instant NOW = Instant.parse("2025-10-28T08:00:00Z");
    private static final String DIGEST = "ef".repeat(32);

    private static JobStore store() {
        return new JobStore();
    }

    private static ProgramJob submit(JobStore live, String board, Instant when) {
        return live.submit(board, DIGEST, JobMode.JTAG, "team-a", when);
    }

    private static ProgramJob submit(JobStore live, String board) {
        return submit(live, board, NOW);
    }

    private static ProgramJob submit(JobStore live, Instant when) {
        return submit(live, "bx-14", when);
    }

    private static ProgramJob submit(JobStore live) {
        return submit(live, "bx-14", NOW);
    }

    private static List<String> ids(List<ProgramJob> jobs) {
        return jobs.stream().map(ProgramJob::id).toList();
    }

    @Test
    void aJobGoesOnTheStore() {
        JobStore live = store();
        ProgramJob job = submit(live);
        assertThat(job.id()).isEqualTo("job-00001");
        assertThat(live.job("job-00001")).isEqualTo(job);
        assertThat(live.queued()).containsExactly(job);
        assertThat(live.size()).isEqualTo(1);
    }

    @Test
    void aJobNobodySubmittedIsANotFound() {
        assertThatThrownBy(() -> store().job("job-09999")).isInstanceOf(NotFound.class);
        assertThatThrownBy(() -> store().holdUntil("job-09999", NOW)).isInstanceOf(NotFound.class);
    }

    @Test
    void theOldestQueuedJobIsOfferedFirst() {
        JobStore live = store();
        ProgramJob first = submit(live, NOW);
        ProgramJob second = submit(live, "bx-15", NOW.plus(Duration.ofMinutes(1)));
        assertThat(ids(live.ready(NOW.plus(Duration.ofMinutes(2))))).containsExactly(first.id(), second.id());
        assertThat(live.nextReady(NOW.plus(Duration.ofMinutes(2))))
                .map(ProgramJob::id)
                .contains(first.id());
    }

    @Test
    void twoJobsForOneBoardDoNotBothGetOffered() {
        JobStore live = store();
        ProgramJob first = submit(live, NOW);
        submit(live, NOW.plus(Duration.ofMinutes(1)));
        List<ProgramJob> ready = live.ready(NOW.plus(Duration.ofMinutes(2)));
        assertThat(ids(ready)).containsExactly(first.id());
    }

    @Test
    void aRunningJobKeepsItsBoardBusy() {
        JobStore live = store();
        ProgramJob first = submit(live);
        submit(live, NOW.plus(Duration.ofMinutes(1)));
        live.update(first.start(NOW));
        assertThat(live.busyBoards()).containsExactly("bx-14");
        assertThat(live.ready(NOW.plus(Duration.ofMinutes(2)))).isEmpty();
    }

    @Test
    void aFinishedJobFreesTheBoard() {
        JobStore live = store();
        ProgramJob first = submit(live);
        ProgramJob second = submit(live, NOW.plus(Duration.ofMinutes(1)));
        live.update(first.start(NOW).passed(NOW.plus(Duration.ofMinutes(2))));
        assertThat(ids(live.ready(NOW.plus(Duration.ofMinutes(3))))).containsExactly(second.id());
    }

    @Test
    void aBackoffKeepsAJobOffTheReadyList() {
        JobStore live = store();
        ProgramJob job = submit(live);
        live.holdUntil(job.id(), NOW.plusSeconds(30));
        assertThat(live.ready(NOW)).isEmpty();
        assertThat(ids(live.waitingForBackoff(NOW))).containsExactly(job.id());
        assertThat(live.ready(NOW.plusSeconds(31)).getFirst().id()).isEqualTo(job.id());
        assertThat(live.heldUntil(job.id())).contains(NOW.plusSeconds(30));
    }

    @Test
    void aFinishedJobForgetsItsBackoff() {
        JobStore live = store();
        ProgramJob job = submit(live);
        live.holdUntil(job.id(), NOW.plusSeconds(30));
        live.update(job.start(NOW).passed(NOW));
        assertThat(live.heldUntil(job.id())).isEmpty();
    }

    @Test
    void updatingAJobThatIsNotThereIsANotFound() {
        JobStore live = store();
        ProgramJob job = submit(live);
        ProgramJob other = new JobStore().submit("bx-15", DIGEST, JobMode.JTAG, "team-a", NOW);
        live.update(job.start(NOW));
        assertThatThrownBy(() -> new JobStore().update(other.start(NOW))).isInstanceOf(NotFound.class);
    }

    @Test
    void jobsComeBackByBoardAndByState() {
        JobStore live = store();
        submit(live, "bx-14");
        ProgramJob second = submit(live, "bx-15");
        live.update(second.start(NOW));
        assertThat(live.forBoard("BX-0014").stream().map(ProgramJob::board)).containsExactly("bx-14");
        assertThat(ids(live.running())).containsExactly(second.id());
        assertThat(live.lastForBoard("bx-15")).map(ProgramJob::id).contains(second.id());
        assertThat(live.lastForBoard("bx-99")).isEmpty();
    }

    @Test
    void aCancelledJobLeavesTheQueue() {
        JobStore live = store();
        ProgramJob job = submit(live);
        live.cancel(job.id(), NOW.plus(Duration.ofMinutes(1)), "board went to maintenance");
        assertThat(live.queued()).isEmpty();
        assertThat(live.job(job.id()).error()).isEqualTo("board went to maintenance");
    }

    @Test
    void aJobThatNeverFinishedIsAbandonedAfterTheLimit() {
        JobStore live = store();
        ProgramJob job = submit(live);
        live.update(job.start(NOW));
        List<ProgramJob> closed = live.abandonStale(NOW.plus(Duration.ofHours(2)), Duration.ofHours(1));
        assertThat(ids(closed)).containsExactly(job.id());
        assertThat(live.job(job.id()).state()).isEqualTo(JobState.FAILED);
        assertThat(live.job(job.id()).errorCode()).isEqualTo("abandoned");
    }

    @Test
    void aJobInsideTheLimitIsLeftRunning() {
        JobStore live = store();
        ProgramJob job = submit(live);
        live.update(job.start(NOW));
        assertThat(live.abandonStale(NOW.plus(Duration.ofMinutes(10)), Duration.ofHours(1)))
                .isEmpty();
        assertThat(live.job(job.id()).state()).isEqualTo(JobState.RUNNING);
    }

    @Test
    void aLimitOfNothingIsABug() {
        assertThatThrownBy(() -> store().abandonStale(NOW, Duration.ZERO)).isInstanceOf(ValidationError.class);
    }

    @Test
    void idsRunInOrderAndAreStable() {
        JobStore live = store();
        assertThat(submit(live).id()).isEqualTo("job-00001");
        assertThat(submit(live, "bx-15").id()).isEqualTo("job-00002");
        assertThat(ids(live.all())).containsExactly("job-00001", "job-00002");
    }
}
