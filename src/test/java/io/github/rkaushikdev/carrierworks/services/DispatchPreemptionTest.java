package io.github.rkaushikdev.carrierworks.services;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import io.github.rkaushikdev.carrierworks.persistence.UnitOfWork;
import io.github.rkaushikdev.carrierworks.scheduling.PreemptionPolicy;
import io.github.rkaushikdev.carrierworks.scheduling.Request;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DispatchPreemptionTest {

    private static final Instant WHEN = Instant.parse("2026-05-18T11:00:00Z");
    private static final String KINTEX = "device=xc7k325t";
    private static final DispatchOptions PREEMPTING = DispatchOptions.standard().withPreempt(true);

    private BenchDatabase database;

    @BeforeEach
    void open() {
        database = BenchDatabase.inMemory();
    }

    @AfterEach
    void close() {
        database.close();
    }

    /** Run a case in one unit of work, on a bench holding a Kintex as bx-14 and an Artix as bx-15. */
    private void onBench(Consumer<UnitOfWork> body) {
        database.run(unit -> {
            Inventory.addBoard(unit, BoardSetup.kintex("bx-14").build(), WHEN.minus(Duration.ofDays(1)));
            Inventory.addBoard(unit, BoardSetup.artix("bx-15").build(), WHEN.minus(Duration.ofDays(1)));
            body.accept(unit);
        });
    }

    /** A preemptible lease team-a has held on bx-14 for an hour of its three. */
    private static Lease holds(UnitOfWork unit, int priority) {
        return holds(unit, "bx-14", "team-a", priority, 180, 60, true);
    }

    /** A lease somebody has been holding for a while. */
    private static Lease holds(
            UnitOfWork unit, String board, String holder, int priority, int minutes, int ago, boolean preemptible) {
        return Leasing.acquire(
                unit,
                board,
                holder,
                "long capture",
                WHEN.minus(Duration.ofMinutes(ago)),
                new LeaseTerms(minutes, priority, preemptible),
                BenchSettings.defaults());
    }

    private static Request ask(UnitOfWork unit, int priority) {
        return ask(unit, priority, KINTEX);
    }

    private static Request ask(UnitOfWork unit, int priority, String needs) {
        return Scheduling.submit(
                unit,
                "team-b",
                "urgent capture",
                needs,
                WHEN,
                Request.Terms.standard().withPriority(priority));
    }

    @Test
    void preemptionIsOffUnlessItIsAskedFor() {
        onBench(unit -> {
            holds(unit, 5);
            Request request = ask(unit, 1);
            DispatchOutcome outcome = Scheduling.dispatch(unit, WHEN);
            assertThat(outcome.preempted()).isEmpty();
            assertThat(outcome.refusals()).containsKey(request.id());
        });
    }

    @Test
    void anUrgentRequestTakesABoardOffALowPriorityHolder() {
        onBench(unit -> {
            Lease lease = holds(unit, 7);
            Request request = ask(unit, 1);
            DispatchOutcome outcome = Scheduling.dispatch(unit, WHEN, PREEMPTING);
            assertThat(outcome.preempted()).containsExactly(new DispatchOutcome.Preempted(lease.id(), "bx-14"));
            assertThat(outcome.assigned().getFirst().request()).isEqualTo(request.id());
            assertThat(outcome.assigned().getFirst().board()).isEqualTo("bx-14");
        });
    }

    @Test
    void theBumpedHolderLosesTheLease() {
        onBench(unit -> {
            Lease lease = holds(unit, 7);
            ask(unit, 1);
            Scheduling.dispatch(unit, WHEN, PREEMPTING);
            assertThat(unit.leases().get(lease.id()).releasedAt()).isPresent();
            assertThat(unit.leases().forBoard("bx-14", WHEN)).map(Lease::holder).contains("team-b");
        });
    }

    @Test
    void theReleaseSaysWhichRequestTookIt() {
        onBench(unit -> {
            Lease lease = holds(unit, 7);
            Request request = ask(unit, 1);
            Scheduling.dispatch(unit, WHEN, PREEMPTING);
            assertThat(unit.leases().get(lease.id()).releaseReason()).isEqualTo("preempted by " + request.id());
        });
    }

    @Test
    void aLeaseThatDoesNotLoseByEnoughStays() {
        onBench(unit -> {
            holds(unit, 5);
            Request request = ask(unit, 4);
            DispatchOutcome outcome = Scheduling.dispatch(unit, WHEN, PREEMPTING);
            assertThat(outcome.preempted()).isEmpty();
            assertThat(outcome.refusals()).containsKey(request.id());
        });
    }

    @Test
    void aLeaseMarkedUnpreemptibleStays() {
        onBench(unit -> {
            holds(unit, "bx-14", "team-a", 9, 180, 60, false);
            ask(unit, 1);
            assertThat(Scheduling.dispatch(unit, WHEN, PREEMPTING).preempted()).isEmpty();
        });
    }

    @Test
    void aLeaseThatJustStartedStays() {
        onBench(unit -> {
            holds(unit, "bx-14", "team-a", 9, 180, 2, true);
            assertThat(ask(unit, 1)).isNotNull();
            assertThat(Scheduling.dispatch(unit, WHEN, PREEMPTING).preempted()).isEmpty();
        });
    }

    @Test
    void aLeaseAboutToEndStays() {
        onBench(unit -> {
            holds(unit, "bx-14", "team-a", 9, 62, 60, true);
            ask(unit, 1);
            assertThat(Scheduling.dispatch(unit, WHEN, PREEMPTING).preempted()).isEmpty();
        });
    }

    @Test
    void aBoardBeingProgrammedIsNotTaken() {
        onBench(unit -> {
            holds(unit, 9);
            Inventory.moveState(unit, "bx-14", BoardStatus.PROGRAMMING, WHEN, "", "team-a");
            ask(unit, 1);
            assertThat(Scheduling.dispatch(unit, WHEN, PREEMPTING).preempted()).isEmpty();
        });
    }

    @Test
    void aBoardThatDoesNotMatchIsNotTaken() {
        onBench(unit -> {
            holds(unit, "bx-15", "team-a", 9, 180, 60, true);
            Inventory.moveState(unit, "bx-14", BoardStatus.MAINTENANCE, WHEN, "fan", "ops");
            ask(unit, 1, KINTEX);
            assertThat(Scheduling.dispatch(unit, WHEN, PREEMPTING).preempted()).isEmpty();
        });
    }

    @Test
    void aFreeBoardIsAlwaysPreferredToTakingOne() {
        onBench(unit -> {
            holds(unit, 9);
            ask(unit, 1, "");
            DispatchOutcome outcome = Scheduling.dispatch(unit, WHEN, PREEMPTING);
            assertThat(outcome.preempted()).isEmpty();
            assertThat(outcome.assigned().getFirst().board()).isEqualTo("bx-15");
        });
    }

    @Test
    void theCheapestLeaseIsTheOneTaken() {
        onBench(unit -> {
            Inventory.addBoard(
                    unit,
                    BoardSetup.kintex("bx-16")
                            .location("rack-2/shelf-07/slot-1")
                            .serial("210308BB2222")
                            .outlet(4)
                            .build(),
                    WHEN.minus(Duration.ofDays(1)));
            holds(unit, "bx-14", "team-a", 6, 180, 60, true);
            Lease cheap = holds(unit, "bx-16", "team-c", 8, 180, 60, true);
            ask(unit, 1);
            DispatchOutcome outcome = Scheduling.dispatch(unit, WHEN, PREEMPTING);
            assertThat(outcome.preempted()).containsExactly(new DispatchOutcome.Preempted(cheap.id(), "bx-16"));
        });
    }

    @Test
    void aTighterMarginCanBePassedIn() {
        onBench(unit -> {
            holds(unit, 5);
            ask(unit, 4);
            DispatchOutcome outcome =
                    Scheduling.dispatch(unit, WHEN, PREEMPTING.withPolicy(new PreemptionPolicy().withMargin(1)));
            assertThat(outcome.preempted()).isNotEmpty();
        });
    }

    @Test
    void theOutcomeSaysWhatItTook() {
        onBench(unit -> {
            holds(unit, 7);
            ask(unit, 1);
            assertThat(Scheduling.dispatch(unit, WHEN, PREEMPTING).describe()).contains("took lease-00001 off bx-14");
        });
    }
}
