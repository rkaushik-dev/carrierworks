package io.github.rkaushikdev.carrierworks.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.carrierworks.ConfigurationError;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.catalog.CardSpec;
import io.github.rkaushikdev.carrierworks.jtag.AdapterUnit;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class BoardsTest {

    private static Board.Builder fields(String part) {
        return Board.builder("bx-14", "rx-carrier-b", part, "n25q128a13", "rack-2/shelf-04/slot-3")
                .slots(new BoardSlot("fmc0", "fmc-hpc", "fmc-adc-4ch-14b"), new BoardSlot("fmc1", "fmc-lpc"))
                .adapter(new AdapterUnit("210308AB1234", "jtag-hs2"))
                .noPdu();
    }

    private static Board board() {
        return board(UnaryOperator.identity());
    }

    private static Board board(UnaryOperator<Board.Builder> changes) {
        return changes.apply(fields("XC7K325T-2FFG900I")).build();
    }

    @Test
    void aBoardCarriesItsOwnFacts() {
        Board live = board();
        assertThat(live.tag()).isEqualTo("bx-14");
        assertThat(live.device().device()).isEqualTo("xc7k325t");
        assertThat(live.flashSpec().capacityBytes()).isEqualTo(16L * 1024 * 1024);
        assertThat(live.model()).isEqualTo("rx-carrier-b");
        assertThat(live.revision()).isEqualTo("a");
    }

    @Test
    void aSlotHoldsACardThatFitsIt() {
        Board live = board();
        assertThat(live.slot("fmc0").fitted()).contains("fmc-adc-4ch-14b");
        assertThat(live.slot("fmc1").isEmpty()).isTrue();
        assertThat(live.fittedCards().stream().map(CardSpec::name)).containsExactly("fmc-adc-4ch-14b");
    }

    @Test
    void aCardThatDoesNotFitItsConnectorIsRefused() {
        assertThatThrownBy(() -> new BoardSlot("fmc1", "fmc-lpc", "fmc-sfp-quad"))
                .isInstanceOf(ConfigurationError.class);
    }

    @Test
    void aLowPinCountCardGoesInAHighPinCountSlot() {
        BoardSlot slot = new BoardSlot("fmc0", "fmc-hpc", "fmc-adc-2ch-16b");
        assertThat(slot.fitted()).contains("fmc-adc-2ch-16b");
    }

    @Test
    void anFmcPlusSlotTakesAnFmcPlusCardAndItsLanes() {
        Board live = board(b -> b.slots(
                new BoardSlot("fmc0", "fmc-plus", "fmcp-adc-8ch-12b"),
                new BoardSlot("fmc1", "fmc-plus", "fmc-sfp-quad")));
        assertThat(live.transceiversFree()).isEqualTo(4);
        assertThatThrownBy(() -> new BoardSlot("fmc0", "fmc-hpc", "fmcp-adc-8ch-12b"))
                .isInstanceOf(ConfigurationError.class);
    }

    @Test
    void aSlotNobodyNamedIsANotFound() {
        assertThatThrownBy(() -> board().slot("fmc9")).isInstanceOf(NotFound.class);
    }

    @Test
    void twoSlotsCannotShareAName() {
        assertThatThrownBy(
                        () -> board(b -> b.slots(new BoardSlot("fmc0", "fmc-hpc"), new BoardSlot("fmc0", "fmc-lpc"))))
                .isInstanceOf(ConfigurationError.class);
    }

    @Test
    void cardsCannotWantMoreTransceiversThanTheDeviceHas() {
        ConfigurationError caught = catchThrowableOfType(ConfigurationError.class, () -> fields("XC7A35T-2CSG324I")
                .slots(
                        new BoardSlot("fmc0", "fmc-hpc", "fmc-adc-4ch-14b"),
                        new BoardSlot("fmc1", "fmc-hpc", "fmc-sfp-quad"))
                .build());
        assertThat(caught.details()).containsEntry("have", 4);
    }

    @Test
    void theFreeTransceiverCountIsReported() {
        assertThat(board().transceiversFree()).isEqualTo(12);
        assertThat(board(b -> b.slots(List.of())).transceiversFree()).isEqualTo(16);
    }

    @Test
    void aCardWantingAnotherAdjustableRailIsRefused() {
        ConfigurationError caught = catchThrowableOfType(
                ConfigurationError.class,
                () -> board(b -> b.slots(new BoardSlot("fmc0", "fmc-hpc", "fmc-ddr4-sodimm"))
                        .vadjMillivolts(1800)));
        assertThat(caught.details()).containsEntry("wants", 1200);
    }

    @Test
    void theSameCardIsFineOnACarrierStrappedForIt() {
        Board live = board(b ->
                b.slots(new BoardSlot("fmc0", "fmc-hpc", "fmc-ddr4-sodimm")).vadjMillivolts(1200));
        assertThat(live.fittedCards().getFirst().name()).isEqualTo("fmc-ddr4-sodimm");
    }

    @Test
    void aCardWithNoRailRequirementGoesAnywhere() {
        Board live =
                board(b -> b.slots(new BoardSlot("pmod0", "pmod", "pmod-ad2")).vadjMillivolts(2500));
        assertThat(live.cardPowerMw()).isEqualTo(180);
    }

    @Test
    void cardPowerAddsUp() {
        Board live = board(b -> b.slots(
                new BoardSlot("fmc0", "fmc-hpc", "fmc-adc-4ch-14b"), new BoardSlot("pmod0", "pmod", "pmod-ad2")));
        assertThat(live.cardPowerMw()).isEqualTo(9800 + 180);
    }

    @Test
    void aCableThatCannotDriveTheHeaderIsRefused() {
        assertThatThrownBy(() -> board(b -> b.adapter(new AdapterUnit("210308AB1234", "ft2232h-mini"))
                        .headerVoltageMv(1800)))
                .isInstanceOf(ConfigurationError.class);
    }

    @Test
    void theSameCableIsFineOnABoardAtItsLevel() {
        Board live = board(
                b -> b.adapter(new AdapterUnit("210308AB1234", "ft2232h-mini")).headerVoltageMv(3300));
        assertThat(live.adapter()).isPresent();
        assertThat(live.adapter().get().model()).isEqualTo("ft2232h-mini");
    }

    @Test
    void aBoardWithNoCableIsAllowedBecauseTheyGetBorrowed() {
        assertThat(board(Board.Builder::noAdapter).adapter()).isEmpty();
    }

    @Test
    void aBoardWithNoClockCeilingIsRefused() {
        assertThatThrownBy(() -> board(b -> b.maxTckHz(0))).isInstanceOf(ValidationError.class);
    }

    @Test
    void anUnstockedPartOrFlashIsRefused() {
        assertThatThrownBy(() -> fields("XC7V585T-2FFG1157C").build()).isInstanceOf(NotFound.class);
        assertThatThrownBy(() -> Board.builder("bx-14", "rx-carrier-b", "XC7K325T-2FFG900I", "at45db161", "rack-2/4/3")
                        .build())
                .isInstanceOf(NotFound.class);
    }

    @Test
    void fittingACardRebuildsAndRechecksTheBoard() {
        Board live = board().withCard("fmc1", "fmc-adc-2ch-16b");
        assertThat(live.slot("fmc1").fitted()).contains("fmc-adc-2ch-16b");
        assertThat(live.fittedCards()).hasSize(2);
    }

    @Test
    void fittingACardThatBreaksARuleIsRefused() {
        Board live = fields("XC7A100T-2FGG484I")
                .slots(new BoardSlot("fmc0", "fmc-hpc"))
                .build();
        assertThatThrownBy(() -> live.withCard("fmc0", "fmc-ddr4-sodimm")).isInstanceOf(ConfigurationError.class);
    }

    @Test
    void removingACardLeavesTheSlotEmpty() {
        Board live = board().withoutCard("fmc0");
        assertThat(live.slot("fmc0").isEmpty()).isTrue();
        assertThat(live.fittedCards()).isEmpty();
        assertThat(live.emptySlots()).hasSize(2);
    }

    @Test
    void aBoardDescribesItselfForAListing() {
        String described = board().describe();
        assertThat(described).startsWith("bx-14 rx-carrier-b rev a XC7K325T-2FFG900I");
        assertThat(described).contains("fmc-adc-4ch-14b");
        assertThat(board(b -> b.slots(List.of())).describe()).contains("bare");
    }

    @Test
    void aSummaryIsTheShortForm() {
        assertThat(BoardSummary.of(board()))
                .isEqualTo(new BoardSummary(
                        "bx-14", "rx-carrier-b", "xc7k325t", "rack-2/shelf-04/slot-3", List.of("fmc-adc-4ch-14b")));
    }
}
