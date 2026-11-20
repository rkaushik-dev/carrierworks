package io.github.rkaushikdev.carrierworks.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.Requirement;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import io.github.rkaushikdev.carrierworks.persistence.UnitOfWork;
import io.github.rkaushikdev.carrierworks.scheduling.Request;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RequestQueueStoreTest {

    private static final Instant WHEN = Instant.parse("2026-03-25T08:00:00Z");
    private static final String KINTEX = "device=xc7k325t";

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

    private static Request ask(UnitOfWork unit) {
        return ask(unit, "team-a", KINTEX);
    }

    private static Request ask(UnitOfWork unit, String holder, String wants) {
        return ask(unit, holder, wants, WHEN, Request.Terms.standard());
    }

    private static Request ask(UnitOfWork unit, String holder, String wants, Instant when, Request.Terms terms) {
        return Scheduling.submit(unit, holder, "capture run", wants, when, terms);
    }

    private static Request ask(UnitOfWork unit, String holder, int priority) {
        return ask(unit, holder, KINTEX, WHEN, Request.Terms.standard().withPriority(priority));
    }

    @Test
    void aRequestIsStoredWithTheWordsItWasWrittenIn() {
        onBench(unit -> {
            Request request = ask(unit, "team-a", "card=fmc-adc-4ch-14b, transceivers>=4");
            Request readBack = unit.requests().get(request.id());
            assertThat(readBack.requirements().requirements())
                    .extracting(Requirement::text)
                    .containsExactly("card=fmc-adc-4ch-14b", "transceivers>=4");
        });
    }

    @Test
    void requestNumbersCarryOnFromWhatIsStored() {
        onBench(unit -> {
            assertThat(ask(unit).id()).isEqualTo("req-0001");
            assertThat(ask(unit).id()).isEqualTo("req-0002");
        });
    }

    @Test
    void aRequestNobodyMadeIsNotFound() {
        onBench(unit ->
                assertThatThrownBy(() -> unit.requests().get("req-9999")).isInstanceOf(NotFound.class));
    }

    @Test
    void aPriorityOutsideTheRangeIsRefused() {
        onBench(unit -> assertThatThrownBy(() -> ask(unit, "team-a", 11)).isInstanceOf(ValidationError.class));
    }

    @Test
    void anOpenRequestIsWaiting() {
        onBench(unit -> {
            Request request = ask(unit);
            assertThat(Scheduling.waiting(unit, WHEN)).extracting(Request::id).containsExactly(request.id());
        });
    }

    @Test
    void aCancelledRequestStopsWaiting() {
        onBench(unit -> {
            Request request = ask(unit);
            Scheduling.cancel(unit, request.id(), WHEN.plus(Duration.ofMinutes(1)));
            assertThat(Scheduling.waiting(unit, WHEN)).isEmpty();
            assertThat(unit.requests().get(request.id()).isOpen()).isFalse();
        });
    }

    @Test
    void cancellingTwiceIsRefused() {
        onBench(unit -> {
            Request request = ask(unit);
            Scheduling.cancel(unit, request.id(), WHEN.plus(Duration.ofMinutes(1)));
            assertThatThrownBy(() -> Scheduling.cancel(unit, request.id(), WHEN.plus(Duration.ofMinutes(2))))
                    .isInstanceOf(ValidationError.class);
        });
    }

    @Test
    void higherPriorityIsServedFirst() {
        onBench(unit -> {
            ask(unit, "team-a", 6);
            Request urgent = ask(unit, "team-b", 2);
            assertThat(Scheduling.waiting(unit, WHEN).getFirst().id()).isEqualTo(urgent.id());
        });
    }

    @Test
    void atTheSamePriorityTheLongerWaitWins() {
        onBench(unit -> {
            Request early = ask(unit, "team-a", KINTEX, WHEN.minus(Duration.ofHours(2)), Request.Terms.standard());
            ask(unit, "team-b", KINTEX);
            assertThat(Scheduling.waiting(unit, WHEN).getFirst().id()).isEqualTo(early.id());
        });
    }

    @Test
    void aRequestForSomethingOnTheBenchGetsABoard() {
        onBench(unit -> {
            Request request = ask(unit, "team-a", KINTEX);
            DispatchOutcome outcome = Scheduling.dispatch(unit, WHEN);
            assertThat(outcome.assigned().getFirst().request()).isEqualTo(request.id());
            assertThat(outcome.assigned().getFirst().board()).isEqualTo("bx-14");
        });
    }

    @Test
    void aServedRequestLeavesALeaseBehind() {
        onBench(unit -> {
            ask(unit);
            DispatchOutcome outcome = Scheduling.dispatch(unit, WHEN);
            Lease held = unit.leases().forBoard("bx-14", WHEN).orElseThrow();
            assertThat(held.id()).isEqualTo(outcome.assigned().getFirst().lease());
            assertThat(held.holder()).isEqualTo("team-a");
        });
    }

    @Test
    void aServedRequestRecordsWhichBoardItGot() {
        onBench(unit -> {
            Request request = ask(unit);
            Scheduling.dispatch(unit, WHEN);
            assertThat(unit.requests().boardFor(request.id())).isEqualTo("bx-14");
            assertThat(unit.requests().get(request.id()).isOpen()).isFalse();
        });
    }

    @Test
    void aRequestForNothingOnTheBenchWaits() {
        onBench(unit -> {
            Request request = ask(unit, "team-a", "device=xcvu9p");
            DispatchOutcome outcome = Scheduling.dispatch(unit, WHEN);
            assertThat(outcome.assigned()).isEmpty();
            assertThat(outcome.refusals()).containsEntry(request.id(), "nothing free matches what it asked for");
            assertThat(unit.requests().get(request.id()).isOpen()).isTrue();
        });
    }

    @Test
    void twoRequestsNeverGetTheSameBoard() {
        onBench(unit -> {
            ask(unit, "team-a", KINTEX);
            Request second = ask(unit, "team-b", KINTEX);
            DispatchOutcome outcome = Scheduling.dispatch(unit, WHEN);
            assertThat(outcome.assigned()).hasSize(1);
            assertThat(outcome.refusals()).containsKey(second.id());
        });
    }

    @Test
    void twoRequestsForDifferentPartsAreBothServed() {
        onBench(unit -> {
            ask(unit, "team-a", KINTEX);
            ask(unit, "team-b", "device=xc7a100t");
            DispatchOutcome outcome = Scheduling.dispatch(unit, WHEN);
            assertThat(outcome.assigned())
                    .extracting(DispatchOutcome.Assigned::board)
                    .containsExactlyInAnyOrder("bx-14", "bx-15");
        });
    }

    @Test
    void aHolderAtItsQuotaIsSkippedRatherThanRefused() {
        onBench(unit -> {
            Leasing.acquire(unit, "bx-15", "team-a", "earlier run", WHEN.minus(Duration.ofMinutes(10)));
            Request request = ask(unit, "team-a", KINTEX);
            DispatchOutcome outcome =
                    Scheduling.dispatch(unit, WHEN, DispatchOptions.standard().withQuotas(Map.of("team-a", 1)));
            assertThat(outcome.refusals().get(request.id())).contains("quota is 1");
            assertThat(unit.requests().get(request.id()).isOpen()).isTrue();
        });
    }

    @Test
    void aHolderUnderItsQuotaIsServed() {
        onBench(unit -> {
            Request request = ask(unit, "team-a", KINTEX);
            DispatchOutcome outcome =
                    Scheduling.dispatch(unit, WHEN, DispatchOptions.standard().withQuotas(Map.of("team-a", 2)));
            assertThat(outcome.assigned().getFirst().request()).isEqualTo(request.id());
        });
    }

    @Test
    void aDispatchCanBeLimitedToOneBoard() {
        onBench(unit -> {
            ask(unit, "team-a", KINTEX);
            ask(unit, "team-b", "device=xc7a100t");
            DispatchOutcome outcome =
                    Scheduling.dispatch(unit, WHEN, DispatchOptions.standard().withLimit(1));
            assertThat(outcome.assigned()).hasSize(1);
            assertThat(outcome.refusals()).hasSize(1);
        });
    }

    @Test
    void aNegativeLimitIsRefused() {
        onBench(unit -> assertThatThrownBy(() -> Scheduling.dispatch(
                        unit, WHEN, DispatchOptions.standard().withLimit(-1)))
                .isInstanceOf(ValidationError.class));
    }

    @Test
    void aBoardOutOfServiceIsNotHandedOut() {
        onBench(unit -> {
            Inventory.moveState(unit, "bx-14", BoardStatus.MAINTENANCE, WHEN, "fan", "ops");
            Request request = ask(unit, "team-a", KINTEX);
            DispatchOutcome outcome = Scheduling.dispatch(unit, WHEN);
            assertThat(outcome.refusals()).containsKey(request.id());
        });
    }

    @Test
    void anExpiredLeaseIsSweptBeforeThePass() {
        onBench(unit -> {
            Leasing.acquire(unit, "bx-14", "team-b", "old run", WHEN.minus(Duration.ofHours(3)), 30);
            Request request = ask(unit, "team-a", KINTEX);
            DispatchOutcome outcome = Scheduling.dispatch(unit, WHEN);
            assertThat(outcome.assigned().getFirst().request()).isEqualTo(request.id());
        });
    }

    @Test
    void anEmptyQueueSaysSo() {
        onBench(unit -> assertThat(Scheduling.dispatch(unit, WHEN).describe()).isEqualTo("nothing waiting"));
    }

    @Test
    void theOutcomeReadsAsOneLine() {
        onBench(unit -> {
            ask(unit);
            assertThat(Scheduling.dispatch(unit, WHEN).describe()).contains("req-0001 -> bx-14");
        });
    }
}
