package io.github.rkaushikdev.carrierworks.power;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.carrierworks.PduChannel;
import io.github.rkaushikdev.carrierworks.PowerFault;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import org.junit.jupiter.api.Test;

class PduTest {

    private static final PduChannel OUTLET_7 = PduChannel.parse("pdu-a/outlet-07");

    private static Board board() {
        return BoardSetup.kintex();
    }

    private static PowerController controller() {
        return new PowerController(new FakePdu());
    }

    @Test
    void theFakeSatisfiesTheDriverProtocol() {
        assertThat(new FakePdu()).isInstanceOf(PduDriver.class);
    }

    @Test
    void aBoardStartsOffAndCanBeSwitchedOn() {
        PowerController live = controller();
        assertThat(live.isOn(board())).isFalse();
        assertThat(live.powerOn(board())).isTrue();
        assertThat(live.isOn(board())).isTrue();
    }

    @Test
    void switchingOnABoardThatIsOnChangesNothing() {
        PowerController live = controller();
        live.powerOn(board());
        assertThat(live.powerOn(board())).isFalse();
    }

    @Test
    void switchingOffWorksTheSameWay() {
        PowerController live = controller();
        live.powerOn(board());
        assertThat(live.powerOff(board())).isTrue();
        assertThat(live.powerOff(board())).isFalse();
        assertThat(live.isOn(board())).isFalse();
    }

    @Test
    void onlyTheBoardSOwnOutletIsTouched() {
        FakePdu driver = new FakePdu();
        PowerController live = new PowerController(driver);
        live.powerOn(board());
        assertThat(driver.commands()).containsExactly("on pdu-a/outlet-07");
    }

    @Test
    void aBoardWithNoOutletCannotBeSwitched() {
        PowerController live = controller();
        ValidationError caught = catchThrowableOfType(
                ValidationError.class,
                () -> live.powerOn(BoardSetup.kintex("bx-14").noOutlet().build()));
        assertThat(caught.details()).containsEntry("board", "bx-14");
    }

    @Test
    void aCycleGoesOffThenOn() {
        FakePdu driver = new FakePdu();
        PowerController live = new PowerController(driver);
        live.powerOn(board());
        driver.clearCommands();
        live.cycle(board());
        assertThat(driver.commands()).containsExactly("off pdu-a/outlet-07", "on pdu-a/outlet-07");
        assertThat(live.isOn(board())).isTrue();
    }

    @Test
    void aCycleHoldsTheBoardOffLongEnoughToResetIt() {
        PowerController live = controller();
        assertThat(live.cycle(board())).isEqualTo(PowerController.MINIMUM_OFF_MS);
        assertThat(live.cycle(board(), 50)).isEqualTo(PowerController.MINIMUM_OFF_MS);
        assertThat(live.cycle(board(), 2000)).isEqualTo(2000);
    }

    @Test
    void aControllerWithTooShortAMinimumIsRefused() {
        assertThatThrownBy(() -> new PowerController(new FakePdu(), 10)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aUnitThatDoesNotAnswerIsARetryableFault() {
        FakePdu driver = new FakePdu().withUnreachable("pdu-a");
        PowerController live = new PowerController(driver);
        PowerFault caught = catchThrowableOfType(PowerFault.class, () -> live.powerOn(board()));
        assertThat(caught.retryable()).isTrue();
    }

    @Test
    void currentIsZeroWhileABoardIsOff() {
        PowerController live = controller();
        assertThat(live.drawMa(board())).isZero();
    }

    @Test
    void currentComesBackOnceABoardIsOn() {
        FakePdu driver = new FakePdu().withCurrent(OUTLET_7, 1400);
        PowerController live = new PowerController(driver);
        live.powerOn(board());
        assertThat(live.drawMa(board())).isEqualTo(1400);
    }

    @Test
    void anOrdinaryDrawIsInsideTheBudget() {
        FakePdu driver = new FakePdu().withCurrent(OUTLET_7, 900);
        PowerController live = new PowerController(driver);
        live.powerOn(board());
        assertThat(live.overBudget(board())).isFalse();
    }

    @Test
    void aBoardPullingFarMoreThanItsCardsAccountForIsFlagged() {
        FakePdu driver = new FakePdu().withCurrent(OUTLET_7, 4000);
        PowerController live = new PowerController(driver);
        live.powerOn(board());
        assertThat(live.overBudget(board())).isTrue();
    }

    @Test
    void theHeadroomIsSettableForACarrierThatRunsWarm() {
        FakePdu driver = new FakePdu().withCurrent(OUTLET_7, 1500);
        PowerController live = new PowerController(driver);
        live.powerOn(board());
        assertThat(live.overBudget(board(), 100)).isTrue();
        assertThat(live.overBudget(board(), 12000)).isFalse();
    }

    @Test
    void twoBoardsOnOneUnitAreSwitchedIndependently() {
        FakePdu driver = new FakePdu();
        PowerController live = new PowerController(driver);
        Board first = board();
        Board second = BoardSetup.kintex("bx-15")
                .location("rack-2/shelf-05/slot-1")
                .noCable()
                .outlet(8)
                .build();
        live.powerOn(first);
        assertThat(live.isOn(first)).isTrue();
        assertThat(live.isOn(second)).isFalse();
        assertThat(driver.isOn(new PduChannel("pdu-a", 7))).isTrue();
        assertThat(driver.isOn(new PduChannel("pdu-a", 8))).isFalse();
    }
}
