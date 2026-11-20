package io.github.rkaushikdev.carrierworks.inventory;

import static io.github.rkaushikdev.carrierworks.inventory.FaultKind.CHAIN;
import static io.github.rkaushikdev.carrierworks.inventory.FaultKind.POWER;
import static io.github.rkaushikdev.carrierworks.inventory.FaultKind.THERMAL;
import static io.github.rkaushikdev.carrierworks.inventory.FaultKind.VERIFY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.ValidationError;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FaultsTest {

    private static final Instant NOW = Instant.parse("2025-07-14T18:00:00Z");

    @SafeVarargs
    private static FaultLog logWith(Map.Entry<FaultKind, Duration>... faults) {
        FaultLog log = new FaultLog();
        for (Map.Entry<FaultKind, Duration> fault : faults) {
            log.add("bx-14", fault.getKey(), NOW.minus(fault.getValue()));
        }
        return log;
    }

    private static Map.Entry<FaultKind, Duration> fault(FaultKind kind, Duration ago) {
        return Map.entry(kind, ago);
    }

    @Test
    void aFaultRecordsWhatAndWhen() {
        FaultRecord fault = new FaultRecord("BX-0014", CHAIN, NOW, "all ones", "job-1");
        assertThat(fault.board()).isEqualTo("bx-14");
        assertThat(fault.describe()).isEqualTo("bx-14 chain at 2025-07-14T18:00:00+00:00: all ones [job-1]");
    }

    @Test
    void aFaultKindNobodyHasIsRefused() {
        assertThatThrownBy(() -> new FaultRecord("bx-14", FaultKind.parse("gremlins"), NOW))
                .isInstanceOf(ValidationError.class);
    }

    /**
     * A fault time with no zone is refused. A fault keeps its moment as an {@link Instant}, which is
     * always UTC, so a zoneless time cannot be handed in; the test holds the type to that.
     */
    @Test
    void faultTimesAreInUtc() {
        assertThat(Arrays.stream(FaultRecord.class.getRecordComponents())
                        .filter(component -> component.getName().equals("at"))
                        .<Object>map(component -> component.getType()))
                .containsExactly(Instant.class);
    }

    @Test
    void faultsAreKeptPerBoard() {
        FaultLog log = new FaultLog();
        log.add("bx-14", CHAIN, NOW);
        log.add("bx-15", POWER, NOW);
        assertThat(log.forBoard("bx-14")).hasSize(1);
        assertThat(log.boards()).containsExactly("bx-14", "bx-15");
        assertThat(log.all()).hasSize(2);
    }

    @Test
    void theLastFaultOnABoardComesBack() {
        FaultLog log = logWith(fault(CHAIN, Duration.ofDays(2)), fault(VERIFY, Duration.ofHours(1)));
        assertThat(log.last("bx-14")).isPresent();
        assertThat(log.last("bx-14").orElseThrow().kind()).isEqualTo(VERIFY);
        assertThat(log.last("bx-15")).isEmpty();
    }

    @Test
    void aWindowOnlyCountsWhatIsInsideIt() {
        FaultLog log = logWith(
                fault(CHAIN, Duration.ofDays(9)), fault(CHAIN, Duration.ofDays(3)), fault(CHAIN, Duration.ofHours(2)));
        assertThat(log.count("bx-14", NOW, Duration.ofDays(7))).isEqualTo(2);
        assertThat(log.count("bx-14", NOW, Duration.ofDays(1))).isEqualTo(1);
        assertThat(log.count("bx-14", NOW, Duration.ofDays(30))).isEqualTo(3);
    }

    @Test
    void aWindowCanBeAskedAboutOneKind() {
        FaultLog log = logWith(fault(CHAIN, Duration.ofHours(1)), fault(VERIFY, Duration.ofHours(2)));
        assertThat(log.count("bx-14", NOW, Duration.ofDays(1), VERIFY)).isEqualTo(1);
        assertThat(log.count("bx-14", NOW, Duration.ofDays(1), POWER)).isZero();
    }

    @Test
    void aWindowOfNoLengthIsABug() {
        assertThatThrownBy(() -> new FaultLog().count("bx-14", NOW, Duration.ZERO))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aQuietBoardIsNotQuarantined() {
        QuarantinePolicy policy = new QuarantinePolicy();
        FaultLog log = logWith(fault(CHAIN, Duration.ofDays(2)));
        assertThat(policy.verdict(log, "bx-14", NOW)).isEmpty();
        assertThat(policy.shouldQuarantine(log, "bx-14", NOW)).isFalse();
    }

    @Test
    void threeFaultsInAWeekIsEnough() {
        QuarantinePolicy policy = new QuarantinePolicy();
        FaultLog log = logWith(
                fault(CHAIN, Duration.ofDays(6)), fault(POWER, Duration.ofDays(3)), fault(THERMAL, Duration.ofDays(1)));
        assertThat(policy.verdict(log, "bx-14", NOW)).contains("3 faults inside 7 days");
    }

    @Test
    void theSameThreeFaultsSpreadOutAreNot() {
        QuarantinePolicy policy = new QuarantinePolicy();
        FaultLog log = logWith(
                fault(CHAIN, Duration.ofDays(20)),
                fault(POWER, Duration.ofDays(12)),
                fault(THERMAL, Duration.ofDays(9)));
        assertThat(policy.verdict(log, "bx-14", NOW)).isEmpty();
    }

    @Test
    void twoFaultsInAnAfternoonIsEnoughOnItsOwn() {
        QuarantinePolicy policy = new QuarantinePolicy();
        FaultLog log = logWith(fault(CHAIN, Duration.ofHours(6)), fault(POWER, Duration.ofHours(1)));
        assertThat(policy.verdict(log, "bx-14", NOW)).contains("2 faults inside 8 hours");
    }

    @Test
    void twoVerifyFailuresAreReportedAsVerifyFailures() {
        QuarantinePolicy policy = new QuarantinePolicy();
        FaultLog log = logWith(fault(VERIFY, Duration.ofDays(5)), fault(VERIFY, Duration.ofDays(1)));
        assertThat(policy.verdict(log, "bx-14", NOW)).contains("2 verify failures inside 7 days");
    }

    @Test
    void theSharpestReasonWinsWhenSeveralApply() {
        QuarantinePolicy policy = new QuarantinePolicy();
        FaultLog log = logWith(
                fault(VERIFY, Duration.ofHours(3)),
                fault(VERIFY, Duration.ofHours(1)),
                fault(CHAIN, Duration.ofDays(2)));
        assertThat(policy.verdict(log, "bx-14", NOW)).contains("2 verify failures inside 7 days");
    }

    @Test
    void theThresholdsAreSettableForABenchWithOlderBoards() {
        QuarantinePolicy policy = new QuarantinePolicy().withThresholds(6, 4, 3);
        FaultLog log = logWith(
                fault(CHAIN, Duration.ofDays(6)), fault(POWER, Duration.ofDays(3)), fault(THERMAL, Duration.ofDays(1)));
        assertThat(policy.verdict(log, "bx-14", NOW)).isEmpty();
    }

    @Test
    void aPolicyWithAShortWindowLongerThanItsLongOneIsRefused() {
        assertThatThrownBy(() -> new QuarantinePolicy().withWindows(Duration.ofHours(1), Duration.ofDays(1)))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aPolicyWithAThresholdOfNothingIsRefused() {
        assertThatThrownBy(() -> new QuarantinePolicy().withThresholds(0, 2, 2)).isInstanceOf(ValidationError.class);
    }

    @Test
    void maintenanceClearsABoardAndSaysHowManyItDropped() {
        FaultLog log = logWith(fault(CHAIN, Duration.ofHours(2)), fault(VERIFY, Duration.ofHours(1)));
        log.add("bx-15", CHAIN, NOW);
        assertThat(log.clear("bx-14")).isEqualTo(2);
        assertThat(log.forBoard("bx-14")).isEmpty();
        assertThat(log.forBoard("bx-15")).hasSize(1);
    }

    @Test
    void aClearedBoardStopsBeingQuarantineWorthy() {
        QuarantinePolicy policy = new QuarantinePolicy();
        FaultLog log = logWith(fault(VERIFY, Duration.ofHours(3)), fault(VERIFY, Duration.ofHours(1)));
        assertThat(policy.shouldQuarantine(log, "bx-14", NOW)).isTrue();
        log.clear("bx-14");
        assertThat(policy.shouldQuarantine(log, "bx-14", NOW)).isFalse();
    }

    @Test
    void aOneDayWindowReadsAsADay() {
        QuarantinePolicy policy = new QuarantinePolicy().withWindows(Duration.ofDays(1), Duration.ofHours(1));
        FaultLog log = logWith(fault(VERIFY, Duration.ofHours(5)), fault(VERIFY, Duration.ofHours(2)));
        assertThat(policy.verdict(log, "bx-14", NOW)).contains("2 verify failures inside a day");
    }
}
