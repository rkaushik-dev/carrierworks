package io.github.rkaushikdev.carrierworks.inventory;

import static io.github.rkaushikdev.carrierworks.inventory.BoardStatus.AVAILABLE;
import static io.github.rkaushikdev.carrierworks.inventory.BoardStatus.FAULTED;
import static io.github.rkaushikdev.carrierworks.inventory.BoardStatus.LEASED;
import static io.github.rkaushikdev.carrierworks.inventory.BoardStatus.MAINTENANCE;
import static io.github.rkaushikdev.carrierworks.inventory.BoardStatus.PROGRAMMING;
import static io.github.rkaushikdev.carrierworks.inventory.BoardStatus.QUARANTINED;
import static io.github.rkaushikdev.carrierworks.inventory.BoardStatus.RETIRED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.carrierworks.ValidationError;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class StateTest {

    private static final Instant WHEN = Instant.parse("2025-07-09T14:00:00Z");

    private static BoardState state() {
        return new BoardState(AVAILABLE, WHEN);
    }

    private static BoardState state(BoardStatus name, String reason) {
        return new BoardState(name, WHEN, reason);
    }

    @Test
    void theSevenStatesAreTheOnesOnTheWhiteboard() {
        assertThat(Arrays.stream(BoardStatus.values()).map(BoardStatus::value))
                .containsExactly(
                        "available", "leased", "programming", "faulted", "quarantined", "maintenance", "retired");
    }

    @Test
    void aBoardGoesFromAvailableToLeasedAndBack() {
        BoardState leased = state().move(LEASED, WHEN.plus(Duration.ofMinutes(1)));
        assertThat(leased.state()).isEqualTo(LEASED);
        assertThat(leased.move(AVAILABLE, WHEN.plus(Duration.ofHours(1))).state())
                .isEqualTo(AVAILABLE);
    }

    @Test
    void programmingOnlyHappensWhileABoardIsLeased() {
        assertThat(LEASED.canMoveTo(PROGRAMMING)).isTrue();
        assertThat(AVAILABLE.canMoveTo(PROGRAMMING)).isFalse();
    }

    @Test
    void aRefusalNamesWhatWasAllowedInstead() {
        ValidationError caught = catchThrowableOfType(ValidationError.class, () -> state().move(PROGRAMMING, WHEN));
        assertThat(caught.details()).containsEntry("allowed", "leased, faulted, maintenance, retired");
    }

    @Test
    void aQuarantinedBoardCannotGoStraightBackIntoService() {
        BoardState quarantined = state(QUARANTINED, "three chain faults this week");
        assertThat(QUARANTINED.canMoveTo(AVAILABLE)).isFalse();
        assertThat(quarantined.moves()).containsExactly(MAINTENANCE, RETIRED);
    }

    @Test
    void aQuarantinedBoardComesBackThroughMaintenance() {
        BoardState quarantined = state(QUARANTINED, "three chain faults this week");
        BoardState fixed = quarantined.move(MAINTENANCE, WHEN.plus(Duration.ofDays(1)), "reflowed the header");
        assertThat(fixed.move(AVAILABLE, WHEN.plus(Duration.ofDays(2))).usable())
                .isTrue();
    }

    @Test
    void retiredIsTheEnd() {
        BoardState retired = state(RETIRED, "device lifted for spares");
        assertThat(retired.terminal()).isTrue();
        assertThat(retired.moves()).isEmpty();
        assertThatThrownBy(() -> retired.move(AVAILABLE, WHEN.plus(Duration.ofDays(1))))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aFaultedBoardCanComeBackWithoutMaintenance() {
        BoardState faulted = state(FAULTED, "chain read as all ones");
        assertThat(faulted.move(AVAILABLE, WHEN.plus(Duration.ofMinutes(5))).usable())
                .isTrue();
    }

    @Test
    void theStatesThatTakeABoardOutOfServiceAreNamed() {
        assertThat(state(FAULTED, "x").outOfService()).isTrue();
        assertThat(state(QUARANTINED, "x").outOfService()).isTrue();
        assertThat(state(MAINTENANCE, "x").outOfService()).isTrue();
        assertThat(state(RETIRED, "x").outOfService()).isTrue();
        assertThat(state().outOfService()).isFalse();
    }

    @Test
    void busyCoversLeasedAndProgramming() {
        assertThat(state(LEASED, "").busy()).isTrue();
        assertThat(state(PROGRAMMING, "").busy()).isTrue();
        assertThat(state().busy()).isFalse();
        assertThat(state(FAULTED, "x").busy()).isFalse();
    }

    @Test
    void onlyAvailableIsUsable() {
        for (BoardStatus name : BoardStatus.values()) {
            String reason = List.of(AVAILABLE, LEASED, PROGRAMMING).contains(name) ? "" : "because";
            assertThat(new BoardState(name, WHEN, reason).usable()).isEqualTo(name == AVAILABLE);
        }
    }

    @Test
    void takingABoardOutOfServiceNeedsAReason() {
        assertThatThrownBy(() -> new BoardState(FAULTED, WHEN)).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> new BoardState(QUARANTINED, WHEN, "   ")).isInstanceOf(ValidationError.class);
    }

    @Test
    void ordinaryStatesDoNotNeedOne() {
        assertThat(new BoardState(LEASED, WHEN).reason()).isEmpty();
    }

    @Test
    void aStateNobodyHasIsRefused() {
        assertThatThrownBy(() -> BoardStatus.parse("on-fire")).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> BoardStatus.parse("on-fire").moves()).isInstanceOf(ValidationError.class);
        assertThat(BoardStatus.parse(" Leased ")).isEqualTo(LEASED);
    }

    /**
     * A timestamp with no zone is refused. The state keeps its moment as an {@link Instant}, which is
     * always UTC, so a zoneless time cannot be handed in; the test holds the type to that.
     */
    @Test
    void timestampsHaveToBeInUtc() {
        assertThat(Arrays.stream(BoardState.class.getRecordComponents())
                        .filter(component -> component.getName().equals("since"))
                        .<Object>map(component -> component.getType()))
                .containsExactly(Instant.class);
    }

    @Test
    void aBoardCannotChangeStateBackwardsInTime() {
        assertThatThrownBy(() -> state().move(LEASED, WHEN.minus(Duration.ofMinutes(1))))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void howLongABoardHasBeenWhereItIs() {
        assertThat(state().heldSeconds(WHEN.plus(Duration.ofMinutes(30)))).isEqualTo(1800);
        assertThatThrownBy(() -> state().heldSeconds(WHEN.minus(Duration.ofSeconds(1))))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aStateDescribesItselfWithItsReasonAndWhoDidIt() {
        BoardState faulted = new BoardState(FAULTED, WHEN, "chain read as all ones", "nightly");
        assertThat(faulted.describe())
                .isEqualTo("faulted since 2025-07-09T14:00:00+00:00: chain read as all ones (nightly)");
        assertThat(state().describe()).isEqualTo("available since 2025-07-09T14:00:00+00:00");
    }

    @Test
    void everyStateThatIsNotTerminalHasSomewhereToGo() {
        for (BoardStatus name : BoardStatus.values()) {
            List<BoardStatus> moves = name.moves();
            assertThat(moves.isEmpty()).isEqualTo(name == RETIRED);
        }
    }
}
