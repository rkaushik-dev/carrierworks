package io.github.rkaushikdev.carrierworks.thermal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.carrierworks.ThermalLimits;
import io.github.rkaushikdev.carrierworks.inventory.BoardBook;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import io.github.rkaushikdev.carrierworks.leases.LeaseStore;
import io.github.rkaushikdev.carrierworks.power.FakePdu;
import io.github.rkaushikdev.carrierworks.power.PowerController;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ThermalSupervisorTest {

    private static final Instant NOW = Instant.parse("2025-09-24T13:00:00Z");

    private static ThermalLimits limits() {
        return new ThermalLimits(76_000, 86_000, 90_000);
    }

    /** A supervisor and the stores and outlets behind it, so a test can set them up and look at them. */
    private record Bench(ThermalSupervisor supervisor, BoardBook book, LeaseStore leases, FakePdu driver) {}

    /** bx-14 with bx-15 one shelf above it in the same column, both on switched outlets. */
    private static Bench bench() {
        BoardBook book = new BoardBook(NOW.minus(Duration.ofDays(1)));
        book.add(BoardSetup.kintex("bx-14")
                .location("rack-2/shelf-04/slot-3")
                .noCable()
                .outlet(7)
                .build());
        book.add(BoardSetup.artix("bx-15")
                .location("rack-2/shelf-05/slot-3")
                .noCable()
                .outlet(8)
                .build());
        LeaseStore leases = new LeaseStore();
        FakePdu driver = new FakePdu();
        ThermalSupervisor supervisor =
                new ThermalSupervisor(book, new AlarmBook(limits()), leases, new PowerController(driver));
        return new Bench(supervisor, book, leases, driver);
    }

    /** {@code count} readings for one board, twenty seconds apart, the newest at {@code now}. */
    private static ThermalLog readings(String board, int value, Instant now, int count) {
        ThermalLog log = new ThermalLog();
        for (int index = 0; index < count; index++) {
            log.add(board, now.minus(Duration.ofSeconds(20L * index)), value);
        }
        return log;
    }

    private static ThermalLog readings(String board, int value, Instant now) {
        return readings(board, value, now, 2);
    }

    private static ThermalLog readings(String board, int value) {
        return readings(board, value, NOW);
    }

    private static List<SupervisorAction.Kind> kinds(List<SupervisorAction> actions) {
        return actions.stream().map(SupervisorAction::action).toList();
    }

    @Test
    void aCoolBenchProducesNothing() {
        Bench bench = bench();
        ThermalLog log = readings("bx-14", 60_000);
        log.add("bx-15", NOW, 61_000);
        assertThat(bench.supervisor().tick(log, NOW)).isEmpty();
    }

    @Test
    void aWarmBoardIsNotedButLeftAlone() {
        Bench bench = bench();
        ThermalLog log = readings("bx-14", 78_000);
        log.add("bx-15", NOW, 60_000);
        List<SupervisorAction> actions = bench.supervisor().tick(log, NOW);
        assertThat(kinds(actions)).containsExactly(SupervisorAction.Kind.NOTE);
        assertThat(bench.book().state("bx-14").usable()).isTrue();
    }

    @Test
    void aCriticalBoardWarnsAndNamesWhatIsDownwind() {
        Bench bench = bench();
        ThermalLog log = readings("bx-14", 88_000);
        log.add("bx-15", NOW, 60_000);
        List<SupervisorAction> actions = bench.supervisor().tick(log, NOW);
        assertThat(actions.getFirst().action()).isEqualTo(SupervisorAction.Kind.WARN);
        assertThat(actions.getFirst().affected()).containsExactly("bx-15");
        assertThat(actions.getFirst().describe()).contains("downwind: bx-15");
    }

    @Test
    void aBoardOverTheShutdownThresholdLosesItsPowerAndItsLease() {
        Bench bench = bench();
        bench.supervisor().power().orElseThrow().powerOn(bench.book().board("bx-14"));
        Lease lease = bench.leases().acquire("bx-14", "team-a", "capture", NOW);
        ThermalLog log = readings("bx-14", 95_000);
        List<SupervisorAction> actions = bench.supervisor().tick(log, NOW);
        assertThat(actions.getFirst().action()).isEqualTo(SupervisorAction.Kind.SHUTDOWN);
        assertThat(bench.driver().isOn(bench.book().board("bx-14").pdu().orElseThrow()))
                .isFalse();
        assertThat(bench.leases().lease(lease.id()).releaseReason()).isEqualTo("thermal shutdown");
        assertThat(bench.book().state("bx-14").state()).isEqualTo(BoardStatus.FAULTED);
    }

    @Test
    void aShutdownHappensWhateverTheLeasePriorityIs() {
        Bench bench = bench();
        bench.leases().acquire("bx-14", "release", "verification", NOW, LeaseStore.DEFAULT_MINUTES, 1, true);
        List<SupervisorAction> actions = bench.supervisor().tick(readings("bx-14", 96_000), NOW);
        assertThat(actions.getFirst().action()).isEqualTo(SupervisorAction.Kind.SHUTDOWN);
    }

    @Test
    void aQuietBoardNobodyHoldsIsLeftAlone() {
        Bench bench = bench();
        assertThat(bench.supervisor().tick(new ThermalLog(), NOW)).isEmpty();
    }

    @Test
    void aQuietBoardSomebodyHoldsIsWatchedFirst() {
        Bench bench = bench();
        bench.leases().acquire("bx-14", "team-a", "capture", NOW);
        List<SupervisorAction> actions = bench.supervisor().tick(new ThermalLog(), NOW);
        assertThat(kinds(actions)).containsExactly(SupervisorAction.Kind.WATCH);
        assertThat(bench.book().state("bx-14").usable()).isTrue();
    }

    @Test
    void aBoardThatStaysQuietIsShutDownAfterTheGrace() {
        Bench bench = bench();
        bench.leases().acquire("bx-14", "team-a", "capture", NOW, 120);
        bench.supervisor().tick(new ThermalLog(), NOW);
        Instant later = NOW.plus(Duration.ofMinutes(6));
        List<SupervisorAction> actions = bench.supervisor().tick(new ThermalLog(), later);
        assertThat(actions.getFirst().action()).isEqualTo(SupervisorAction.Kind.SHUTDOWN);
        assertThat(actions.getFirst().reason()).isEqualTo("stopped reporting while in use");
    }

    @Test
    void aBoardThatStartsReportingAgainInsideTheGraceIsFine() {
        Bench bench = bench();
        bench.leases().acquire("bx-14", "team-a", "capture", NOW, 120);
        bench.supervisor().tick(new ThermalLog(), NOW);
        Instant later = NOW.plus(Duration.ofMinutes(2));
        List<SupervisorAction> actions = bench.supervisor().tick(readings("bx-14", 61_000, later), later);
        assertThat(actions).isEmpty();
        assertThat(bench.book().state("bx-14").usable()).isTrue();
    }

    @Test
    void aSupervisorWithoutAPowerControllerStillFaultsTheBoard() {
        BoardBook book = new BoardBook(NOW.minus(Duration.ofDays(1)));
        book.add(BoardSetup.kintex("bx-14").bare().build());
        ThermalSupervisor supervisor = new ThermalSupervisor(book, new AlarmBook(limits()), new LeaseStore());
        List<SupervisorAction> actions = supervisor.tick(readings("bx-14", 95_000), NOW);
        assertThat(actions.getFirst().action()).isEqualTo(SupervisorAction.Kind.SHUTDOWN);
        assertThat(book.state("bx-14").state()).isEqualTo(BoardStatus.FAULTED);
    }

    @Test
    void shuttingDownABoardTwiceDoesNotMoveItTwice() {
        Bench bench = bench();
        ThermalLog log = readings("bx-14", 95_000);
        bench.supervisor().tick(log, NOW);
        Instant later = NOW.plus(Duration.ofMinutes(1));
        log.add("bx-14", later, 95_000);
        bench.supervisor().tick(log, later);
        assertThat(bench.book().state("bx-14").state()).isEqualTo(BoardStatus.FAULTED);
    }

    @Test
    void theWorstBoardIsReportedForADashboard() {
        Bench bench = bench();
        ThermalLog log = readings("bx-14", 88_000);
        log.add("bx-15", NOW, 60_000);
        bench.supervisor().tick(log, NOW);
        Optional<ThermalStatus> worst = bench.supervisor().worst();
        assertThat(worst).map(ThermalStatus::board).contains("bx-14");
        assertThat(bench.supervisor().summary()).isEqualTo("1 critical, 1 normal");
    }

    @Test
    void aBenchNobodyHasSampledSummarisesAsSuch() {
        Bench bench = bench();
        assertThat(bench.supervisor().summary()).isEqualTo("nothing being watched");
    }
}
