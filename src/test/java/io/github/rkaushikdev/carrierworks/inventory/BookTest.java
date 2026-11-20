package io.github.rkaushikdev.carrierworks.inventory;

import static io.github.rkaushikdev.carrierworks.inventory.BoardStatus.FAULTED;
import static io.github.rkaushikdev.carrierworks.inventory.BoardStatus.LEASED;
import static io.github.rkaushikdev.carrierworks.inventory.BoardStatus.MAINTENANCE;
import static io.github.rkaushikdev.carrierworks.inventory.BoardStatus.QUARANTINED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.carrierworks.ConfigurationError;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.RequirementSet;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class BookTest {

    private static final Instant WHEN = Instant.parse("2025-07-16T09:00:00Z");

    private static BoardBook book() {
        return new BoardBook(WHEN);
    }

    private static Instant after(Duration span) {
        return WHEN.plus(span);
    }

    @Test
    void aBoardGoesInAndComesBackOut() {
        BoardBook shelf = book();
        shelf.add(BoardSetup.kintex());
        assertThat(shelf.board("BX-0014").model()).isEqualTo("rx-carrier-b");
        assertThat(shelf.has("bx-14")).isTrue();
        assertThat(shelf.size()).isEqualTo(1);
    }

    @Test
    void aBoardNobodyAddedIsANotFound() {
        assertThatThrownBy(() -> book().board("bx-99")).isInstanceOf(NotFound.class);
    }

    @Test
    void aTagCannotBeUsedTwice() {
        BoardBook shelf = book();
        shelf.add(BoardSetup.kintex());
        assertThatThrownBy(() -> shelf.add(BoardSetup.kintex("bx-14")
                        .location("rack-2/shelf-05/slot-1")
                        .build()))
                .isInstanceOf(ConfigurationError.class);
    }

    @Test
    void twoBoardsCannotBeInOneSlot() {
        BoardBook shelf = book();
        shelf.add(BoardSetup.kintex("bx-14").location("rack-2/shelf-04/slot-3").build());
        ConfigurationError caught = catchThrowableOfType(
                ConfigurationError.class,
                () -> shelf.add(BoardSetup.artix("bx-15")
                        .location("rack-2/shelf-04/slot-3")
                        .build()));
        assertThat(caught.details()).containsEntry("holder", "bx-14");
    }

    @Test
    void oneCableCannotBeOnTwoBoards() {
        BoardBook shelf = book();
        shelf.add(BoardSetup.kintex("bx-14").serial("210308AB1234").build());
        ConfigurationError caught = catchThrowableOfType(
                ConfigurationError.class,
                () -> shelf.add(BoardSetup.artix("bx-15")
                        .location("rack-2/shelf-05/slot-1")
                        .serial("210308AB1234")
                        .build()));
        assertThat(caught.details()).containsEntry("serial", "210308AB1234");
    }

    @Test
    void oneOutletCannotFeedTwoBoards() {
        BoardBook shelf = book();
        shelf.add(BoardSetup.kintex("bx-14").outlet(7).build());
        assertThatThrownBy(() -> shelf.add(BoardSetup.artix("bx-15")
                        .location("rack-2/shelf-05/slot-1")
                        .outlet(7)
                        .pduName("pdu-a")
                        .build()))
                .isInstanceOf(ConfigurationError.class);
    }

    @Test
    void boardsWithoutCablesOrOutletsDoNotCollide() {
        BoardBook shelf = book();
        shelf.add(BoardSetup.kintex("bx-14").bare().build());
        shelf.add(BoardSetup.artix("bx-15")
                .location("rack-2/shelf-05/slot-1")
                .bare()
                .build());
        assertThat(shelf.size()).isEqualTo(2);
    }

    @Test
    void aBoardStartsAvailable() {
        BoardBook shelf = book();
        shelf.add(BoardSetup.kintex());
        assertThat(shelf.state("bx-14").usable()).isTrue();
        assertThat(shelf.available()).isEqualTo(shelf.all());
    }

    @Test
    void aBoardCanBeAddedAlreadyOutOfService() {
        BoardBook shelf = book();
        shelf.add(BoardSetup.kintex(), MAINTENANCE, "");
        assertThat(shelf.state("bx-14").outOfService()).isTrue();
        assertThat(shelf.available()).isEmpty();
        assertThat(shelf.state("bx-14").reason()).isEqualTo("added to the bench");
    }

    @Test
    void stateMovesGoThroughTheStateMachine() {
        BoardBook shelf = book();
        shelf.add(BoardSetup.kintex());
        shelf.moveTo("bx-14", LEASED, after(Duration.ofMinutes(5)));
        assertThat(shelf.state("bx-14").state()).isEqualTo(LEASED);
        assertThat(shelf.inState(LEASED).getFirst().tag()).isEqualTo("bx-14");
        assertThatThrownBy(() -> shelf.moveTo("bx-14", QUARANTINED, after(Duration.ofMinutes(6)), "no"))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void replacingABoardKeepsItsState() {
        BoardBook shelf = book();
        shelf.add(BoardSetup.kintex());
        shelf.moveTo("bx-14", LEASED, after(Duration.ofMinutes(5)));
        shelf.replace(shelf.board("bx-14").withCard("fmc1", "fmc-adc-2ch-16b"));
        assertThat(shelf.state("bx-14").state()).isEqualTo(LEASED);
        assertThat(shelf.board("bx-14").fittedCards()).hasSize(2);
    }

    @Test
    void replacingABoardIntoAnOccupiedSlotIsRefused() {
        BoardBook shelf = book();
        shelf.add(BoardSetup.kintex("bx-14").location("rack-2/shelf-04/slot-3").build());
        shelf.add(BoardSetup.artix("bx-15").location("rack-2/shelf-05/slot-1").build());
        Board moved =
                BoardSetup.artix("bx-15").location("rack-2/shelf-04/slot-3").build();
        assertThatThrownBy(() -> shelf.replace(moved)).isInstanceOf(ConfigurationError.class);
    }

    @Test
    void replacingABoardThatIsNotThereIsANotFound() {
        assertThatThrownBy(() -> book().replace(BoardSetup.kintex())).isInstanceOf(NotFound.class);
    }

    @Test
    void aRemovedBoardTakesItsStateWithIt() {
        BoardBook shelf = book();
        shelf.add(BoardSetup.kintex());
        assertThat(shelf.remove("bx-14").tag()).isEqualTo("bx-14");
        assertThat(shelf.has("bx-14")).isFalse();
        assertThatThrownBy(() -> shelf.remove("bx-14")).isInstanceOf(NotFound.class);
    }

    @Test
    void boardsComeBackSortedByTag() {
        BoardBook shelf = book();
        shelf.add(BoardSetup.artix("bx-15")
                .location("rack-2/shelf-05/slot-1")
                .bare()
                .build());
        shelf.add(BoardSetup.kintex("bx-14").bare().build());
        assertThat(shelf.tags()).containsExactly("bx-14", "bx-15");
    }

    @Test
    void boardsCanBeFoundByRackAndByDevice() {
        BoardBook shelf = book();
        shelf.add(BoardSetup.kintex("bx-14")
                .location("rack-2/shelf-04/slot-3")
                .bare()
                .build());
        shelf.add(BoardSetup.artix("bx-15")
                .location("rack-4/shelf-01/slot-1")
                .bare()
                .build());
        assertThat(shelf.inRack("rack-4")).extracting(Board::tag).containsExactly("bx-15");
        assertThat(shelf.withDevice("xc7k325t")).extracting(Board::tag).containsExactly("bx-14");
    }

    @Test
    void matchingOnlyOffersBoardsThatAreFree() {
        BoardBook shelf = book();
        shelf.add(BoardSetup.kintex("bx-14").bare().build());
        shelf.add(BoardSetup.artix("bx-15")
                .location("rack-2/shelf-05/slot-1")
                .bare()
                .build());
        RequirementSet wanted = RequirementSet.parse(List.of("family=kintex7"));
        assertThat(shelf.matching(wanted)).extracting(Board::tag).containsExactly("bx-14");
        shelf.moveTo("bx-14", LEASED, after(Duration.ofMinutes(1)));
        assertThat(shelf.matching(wanted)).isEmpty();
        assertThat(shelf.matching(wanted, false)).extracting(Board::tag).containsExactly("bx-14");
    }

    @Test
    void theBookSummarisesItself() {
        BoardBook shelf = book();
        shelf.add(BoardSetup.kintex("bx-14").bare().build());
        shelf.add(BoardSetup.artix("bx-15")
                .location("rack-2/shelf-05/slot-1")
                .bare()
                .build());
        shelf.moveTo("bx-15", FAULTED, after(Duration.ofMinutes(2)), "chain");
        assertThat(shelf.describe()).isEqualTo("2 boards: 1 available, 1 faulted");
        assertThat(shelf.summaries()).hasSize(2);
        assertThat(shelf.locations()).containsEntry("bx-14", "rack-2/shelf-04/slot-3");
    }

    @Test
    void aBoardNeedsATimeToArrive() {
        assertThatThrownBy(() -> new BoardBook().add(BoardSetup.kintex())).isInstanceOf(ConfigurationError.class);
    }
}
