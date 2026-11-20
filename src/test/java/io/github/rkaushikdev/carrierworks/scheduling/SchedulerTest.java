package io.github.rkaushikdev.carrierworks.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.BenchCalendar;
import io.github.rkaushikdev.carrierworks.RequirementSet;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardBook;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import io.github.rkaushikdev.carrierworks.leases.LeaseStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class SchedulerTest {

    /** A wednesday, 14:00 utc, which is 09:00 in the lab. */
    private static final Instant NOW = Instant.parse("2025-08-20T14:00:00Z");

    private static RequirementSet wanted(String... requirements) {
        return RequirementSet.parse(List.of(requirements));
    }

    private static Instant at(Duration offset) {
        return NOW.plus(offset);
    }

    /** The capture Kintex as bx-14 and the small Artix as bx-15, both available, neither cabled. */
    private static Scheduler bench(UnaryOperator<Scheduler.Builder> rest) {
        BoardBook book = new BoardBook(NOW.minus(Duration.ofDays(1)));
        book.add(BoardSetup.kintex("bx-14").bare().build());
        book.add(BoardSetup.artix("bx-15").bare().build());
        return rest.apply(Scheduler.builder(book, new LeaseStore(), new RequestQueue()))
                .build();
    }

    private static Scheduler bench() {
        return bench(UnaryOperator.identity());
    }

    private static Request.Terms priority(int value) {
        return Request.Terms.standard().withPriority(value);
    }

    @Test
    void aRequestGetsABoard() {
        Scheduler live = bench();
        Request request = live.queue().submit("team-a", "capture", wanted("adc-14bit"), NOW);
        DispatchResult result = live.dispatch(NOW);
        assertThat(result.served()).isEqualTo(1);
        Assignment assignment = result.assignments().getFirst();
        assertThat(assignment.request()).isEqualTo(request.id());
        assertThat(assignment.board()).isEqualTo("bx-14");
        assertThat(live.book().state("bx-14").state()).isEqualTo(BoardStatus.LEASED);
        assertThat(live.queue().request(request.id()).isOpen()).isFalse();
    }

    @Test
    void theSmallestBoardThatFitsIsTheOneHandedOut() {
        Scheduler live = bench();
        live.queue().submit("team-a", "anything", wanted("jtag"), NOW);
        DispatchResult result = live.dispatch(NOW);
        assertThat(result.assignments().getFirst().board()).isEqualTo("bx-15");
    }

    @Test
    void aRequestNothingMatchesStaysOnTheQueueWithAReason() {
        Scheduler live = bench();
        Request request = live.queue().submit("team-a", "capture", wanted("ddr4-sodimm"), NOW);
        DispatchResult result = live.dispatch(NOW);
        assertThat(result.served()).isZero();
        assertThat(result.reasonFor(request.id())).contains("nothing free matches what it asked for");
        assertThat(live.queue().request(request.id()).isOpen()).isTrue();
    }

    @Test
    void twoRequestsTakeTwoBoards() {
        Scheduler live = bench();
        live.queue().submit("team-a", "one", wanted("jtag"), NOW);
        live.queue().submit("team-b", "two", wanted("jtag"), at(Duration.ofMinutes(1)));
        DispatchResult result = live.dispatch(at(Duration.ofMinutes(2)));
        assertThat(result.assignments()).extracting(Assignment::board).containsExactlyInAnyOrder("bx-14", "bx-15");
    }

    @Test
    void aThirdRequestWaitsWhenTheBenchIsFull() {
        Scheduler live = bench();
        live.queue().submit("team-a", "one", wanted("jtag"), NOW);
        live.queue().submit("team-b", "two", wanted("jtag"), NOW);
        Request third = live.queue().submit("team-c", "three", wanted("jtag"), NOW);
        DispatchResult result = live.dispatch(NOW);
        assertThat(result.served()).isEqualTo(2);
        assertThat(result.reasonFor(third.id())).isPresent();
    }

    @Test
    void aQuotaHoldsATeamToItsShare() {
        Scheduler live = bench(builder -> builder.quotas(Map.of("team-a", 1)));
        live.queue().submit("team-a", "one", wanted("jtag"), NOW);
        live.dispatch(NOW);
        Request second = live.queue().submit("team-a", "two", wanted("jtag"), at(Duration.ofMinutes(1)));
        DispatchResult result = live.dispatch(at(Duration.ofMinutes(2)));
        assertThat(result.served()).isZero();
        Optional<String> reason = result.reasonFor(second.id());
        assertThat(reason).isPresent();
        assertThat(reason.get()).contains("quota is 1");
    }

    @Test
    void aJobNeedingHandsWaitsUntilTheLabIsStaffed() {
        Scheduler live = bench(builder -> builder.calendar(BenchCalendar.weekdays()));
        Request request = live.queue()
                .submit(
                        "team-a",
                        "reseat",
                        wanted("jtag"),
                        NOW,
                        Request.Terms.standard().withNeedsHands(true));
        Instant night = at(Duration.ofHours(12));
        DispatchResult result = live.dispatch(night);
        Optional<String> reason = result.reasonFor(request.id());
        assertThat(reason).isPresent();
        assertThat(reason.get()).contains("needs somebody in the lab");
        assertThat(live.dispatch(NOW).served()).isEqualTo(1);
    }

    @Test
    void anUrgentJobTakesABoardOffALowPriorityHolder() {
        Scheduler live = bench();
        live.queue().submit("team-a", "experiment", wanted("adc-14bit"), NOW, priority(8));
        live.dispatch(NOW);
        Instant later = at(Duration.ofMinutes(30));
        live.queue().submit("release", "verification", wanted("adc-14bit"), later, priority(2));
        DispatchResult result = live.dispatch(later);
        assertThat(result.served()).isEqualTo(1);
        Assignment assignment = result.assignments().getFirst();
        assertThat(assignment.board()).isEqualTo("bx-14");
        assertThat(assignment.preempted()).isPresent();
        assertThat(assignment.preempted().get().holder()).isEqualTo("team-a");
        assertThat(assignment.describe()).contains("taken from team-a");
    }

    @Test
    void aPreemptedLeaseIsClosedWithItsReason() {
        Scheduler live = bench();
        live.queue().submit("team-a", "experiment", wanted("adc-14bit"), NOW, priority(8));
        live.dispatch(NOW);
        Instant later = at(Duration.ofMinutes(30));
        live.queue().submit("release", "verification", wanted("adc-14bit"), later, priority(2));
        live.dispatch(later);
        Lease closed = live.leases().lease("lease-00001");
        assertThat(closed.released()).isTrue();
        assertThat(closed.releaseReason()).isEqualTo("preempted");
    }

    @Test
    void anEqualPriorityJobDoesNotGetToPreempt() {
        Scheduler live = bench();
        live.queue().submit("team-a", "experiment", wanted("adc-14bit"), NOW, priority(5));
        live.dispatch(NOW);
        Instant later = at(Duration.ofMinutes(30));
        Request request = live.queue().submit("team-b", "another", wanted("adc-14bit"), later, priority(5));
        DispatchResult result = live.dispatch(later);
        assertThat(result.served()).isZero();
        assertThat(result.reasonFor(request.id())).isPresent();
    }

    @Test
    void releasingABoardPutsItBackIntoService() {
        Scheduler live = bench();
        live.queue().submit("team-a", "one", wanted("jtag"), NOW);
        Lease lease = live.dispatch(NOW).assignments().getFirst().lease();
        live.release(lease.id(), at(Duration.ofMinutes(20)));
        assertThat(live.book().state(lease.board()).usable()).isTrue();
        assertThat(live.leases().forBoard(lease.board(), at(Duration.ofMinutes(21))))
                .isEmpty();
    }

    @Test
    void aFaultedBoardGivesItsLeaseBackAndLeavesService() {
        Scheduler live = bench();
        live.queue().submit("team-a", "one", wanted("jtag"), NOW);
        Assignment assignment = live.dispatch(NOW).assignments().getFirst();
        live.fault(assignment.board(), at(Duration.ofMinutes(5)), "chain read as all ones");
        assertThat(live.book().state(assignment.board()).state()).isEqualTo(BoardStatus.FAULTED);
        assertThat(live.leases().lease(assignment.lease().id()).releaseReason()).isEqualTo("board faulted");
        assertThat(live.book().available()).extracting(Board::tag).doesNotContain(assignment.board());
    }

    @Test
    void aFaultNeedsAReason() {
        Scheduler live = bench();
        assertThatThrownBy(() -> live.fault("bx-14", NOW, "  ")).isInstanceOf(ValidationError.class);
    }

    @Test
    void anExpiredLeaseIsSweptBeforeAnythingElseHappens() {
        Scheduler live = bench();
        live.queue()
                .submit(
                        "team-a",
                        "one",
                        wanted("adc-14bit"),
                        NOW,
                        Request.Terms.standard().withMinutes(30));
        live.dispatch(NOW);
        live.book().moveTo("bx-14", BoardStatus.AVAILABLE, at(Duration.ofMinutes(31)), "", "test");
        live.queue().submit("team-b", "two", wanted("adc-14bit"), at(Duration.ofMinutes(31)));
        DispatchResult result = live.dispatch(at(Duration.ofMinutes(31)));
        assertThat(result.served()).isEqualTo(1);
        assertThat(live.leases().lease("lease-00001").releaseReason()).isEqualTo("expired");
    }

    @Test
    void aTickWithNothingWaitingSaysSo() {
        assertThat(bench().dispatch(NOW).describe()).isEqualTo("nothing waiting");
    }

    @Test
    void theResultDescribesWhatHappened() {
        Scheduler live = bench();
        live.queue().submit("team-a", "one", wanted("jtag"), NOW);
        live.queue().submit("team-b", "two", wanted("ddr4-sodimm"), NOW);
        String described = live.dispatch(NOW).describe();
        assertThat(described).contains("got bx-15");
        assertThat(described).contains("nothing free matches");
    }
}
