package io.github.rkaushikdev.carrierworks.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class PreemptionTest {

    private static final Instant NOW = Instant.parse("2025-08-18T14:00:00Z");

    /** A priority six lease on bx-14, half an hour in and half an hour to go, with some changes. */
    private static Lease lease(String id, String board, UnaryOperator<Lease.Builder> changes) {
        Lease.Builder builder = Lease.builder(
                        id,
                        board,
                        "team-a",
                        "experiment",
                        NOW.minus(Duration.ofMinutes(30)),
                        NOW.plus(Duration.ofMinutes(30)))
                .priority(6);
        return changes.apply(builder).build();
    }

    private static Lease lease(UnaryOperator<Lease.Builder> changes) {
        return lease("lease-00001", "bx-14", changes);
    }

    private static Lease lease() {
        return lease(UnaryOperator.identity());
    }

    private static PreemptionPolicy policy() {
        return new PreemptionPolicy();
    }

    @Test
    void aClearCaseIsAllowed() {
        PreemptionDecision decision = policy().evaluate(lease(), NOW, 2);
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.describe()).isEqualTo("lease-00001 on bx-14 may be taken (preemption)");
    }

    @Test
    void anUnpreemptibleLeaseStays() {
        PreemptionDecision decision = policy().evaluate(lease(changes -> changes.preemptible(false)), NOW, 1);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.blockers().getFirst()).contains("unpreemptible");
    }

    @Test
    void theClaimantHasToBeatTheHolderByTheMargin() {
        Lease live = lease(changes -> changes.priority(5));
        assertThat(policy().evaluate(live, NOW, 4).allowed()).isFalse();
        assertThat(policy().evaluate(live, NOW, 3).allowed()).isTrue();
    }

    @Test
    void theMarginIsSettable() {
        Lease live = lease(changes -> changes.priority(5));
        assertThat(policy().withMargin(1).evaluate(live, NOW, 4).allowed()).isTrue();
    }

    @Test
    void aMarginOfNothingIsRefused() {
        assertThatThrownBy(() -> policy().withMargin(0)).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> policy().withMinimumHeld(Duration.ofMinutes(-1)))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aLeaseThatHasOnlyJustStartedIsLeftAlone() {
        Lease fresh = lease(changes -> changes.takenAt(NOW.minus(Duration.ofMinutes(2))));
        PreemptionDecision decision = policy().evaluate(fresh, NOW, 1);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.blockers().getFirst()).contains("2 minutes old");
    }

    @Test
    void aLeaseThatIsNearlyOverIsLeftAlone() {
        Lease ending = lease(changes -> changes.expiresAt(NOW.plus(Duration.ofMinutes(3))));
        PreemptionDecision decision = policy().evaluate(ending, NOW, 1);
        assertThat(decision.blockers().getFirst()).contains("3 minutes left");
    }

    @Test
    void aBoardBeingProgrammedIsNeverTaken() {
        PreemptionDecision decision = policy().evaluate(lease(), NOW, 1, BoardStatus.PROGRAMMING);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.blockers().getFirst()).isEqualTo("the board is being programmed");
    }

    @Test
    void anEmergencyTakesTheBoardWhateverTheRulesSay() {
        PreemptionDecision decision = policy().evaluate(
                        lease(changes -> changes.preemptible(false).priority(1).takenAt(NOW)),
                        NOW,
                        9,
                        BoardStatus.PROGRAMMING,
                        true);
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.emergency()).isTrue();
        assertThat(decision.describe()).contains("emergency");
    }

    @Test
    void aLeaseThatHasAlreadyEndedCannotBePreempted() {
        Lease over = lease(changes -> changes.expiresAt(NOW.minus(Duration.ofMinutes(1))));
        PreemptionDecision decision = policy().evaluate(over, NOW, 1);
        assertThat(decision.blockers()).containsExactly("that lease is not held any more");
    }

    @Test
    void everyReasonIsReportedNotJustTheFirst() {
        Lease stubborn =
                lease(changes -> changes.preemptible(false).priority(4).takenAt(NOW.minus(Duration.ofMinutes(1))));
        PreemptionDecision decision = policy().evaluate(stubborn, NOW, 5);
        assertThat(decision.blockers()).hasSize(3);
    }

    @Test
    void choosingTakesTheLeastImportantHolder() {
        List<Lease> leases = List.of(
                lease("lease-00001", "bx-14", changes -> changes.priority(6)),
                lease("lease-00002", "bx-15", changes -> changes.priority(8)));
        Optional<Lease> picked = policy().choose(leases, NOW, 2);
        assertThat(picked).map(Lease::id).contains("lease-00002");
    }

    @Test
    void choosingThrowsAwayTheLeastWork() {
        List<Lease> leases = List.of(
                lease("lease-00001", "bx-14", changes -> changes.takenAt(NOW.minus(Duration.ofMinutes(50)))),
                lease("lease-00002", "bx-15", changes -> changes.takenAt(NOW.minus(Duration.ofMinutes(10)))));
        Optional<Lease> picked = policy().choose(leases, NOW, 2);
        assertThat(picked).map(Lease::id).contains("lease-00002");
    }

    @Test
    void choosingSkipsTheBoardThatIsBeingProgrammed() {
        List<Lease> leases = List.of(
                lease("lease-00001", "bx-14", changes -> changes.priority(8)),
                lease("lease-00002", "bx-15", changes -> changes.priority(7)));
        Optional<Lease> picked = policy().choose(leases, NOW, 2, Map.of("bx-14", BoardStatus.PROGRAMMING));
        assertThat(picked).map(Lease::id).contains("lease-00002");
    }

    @Test
    void choosingNothingWhenNothingCanBeTaken() {
        List<Lease> leases = List.of(lease(changes -> changes.preemptible(false)));
        assertThat(policy().choose(leases, NOW, 1)).isEmpty();
        assertThat(policy().choose(List.of(), NOW, 1)).isEmpty();
    }
}
