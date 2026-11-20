package io.github.rkaushikdev.carrierworks.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.carrierworks.CapabilityProfile;
import io.github.rkaushikdev.carrierworks.PduChannel;
import io.github.rkaushikdev.carrierworks.RequirementSet;
import io.github.rkaushikdev.carrierworks.jtag.AdapterUnit;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class ProfileTest {

    private static Board board() {
        return board(UnaryOperator.identity());
    }

    private static Board board(UnaryOperator<Board.Builder> changes) {
        Board.Builder fields = Board.builder(
                        "bx-14", "rx-carrier-b", "XC7K325T-2FFG900I", "n25q128a13", "rack-2/shelf-04/slot-3")
                .slots(new BoardSlot("fmc0", "fmc-hpc", "fmc-adc-4ch-14b"), new BoardSlot("fmc1", "fmc-lpc"))
                .adapter(new AdapterUnit("210308AB1234", "jtag-hs2"))
                .pdu(new PduChannel("pdu-a", 7));
        return changes.apply(fields).build();
    }

    private static RequirementSet wanted(String... requirements) {
        return RequirementSet.parse(List.of(requirements));
    }

    @Test
    void cardCapabilitiesBecomeTokens() {
        CapabilityProfile profile = BoardProfile.of(board());
        assertThat(profile.tokens()).contains("adc-14bit", "trigger-in", "jtag");
    }

    @Test
    void aCabledBoardSaysSoAndSaysWhetherItCanDoFlash() {
        CapabilityProfile profile = BoardProfile.of(board());
        assertThat(profile.tokens()).contains("cabled", "flash-programmable");
    }

    @Test
    void aCableThatCannotDoFlashLeavesTheTokenOff() {
        CapabilityProfile profile =
                BoardProfile.of(board(b -> b.adapter(new AdapterUnit("210308AB1234", "arm-usb-tiny-h"))));
        assertThat(profile.tokens()).contains("cabled").doesNotContain("flash-programmable");
    }

    @Test
    void aBoardWithNoCableIsNotCabled() {
        CapabilityProfile profile = BoardProfile.of(board(Board.Builder::noAdapter));
        assertThat(profile.tokens()).doesNotContain("cabled");
    }

    @Test
    void onlyASwitchedOutletMakesABoardPowerControlled() {
        assertThat(BoardProfile.of(board()).tokens()).contains("power-controlled");
        assertThat(BoardProfile.of(board(Board.Builder::noPdu)).tokens()).doesNotContain("power-controlled");
    }

    @Test
    void anEmptySlotShowsUpAsASpare() {
        assertThat(BoardProfile.of(board()).tokens()).contains("spare-slot");
        Board full = board(b -> b.slots(new BoardSlot("fmc0", "fmc-hpc", "fmc-adc-4ch-14b")));
        assertThat(BoardProfile.of(full).tokens()).doesNotContain("spare-slot");
    }

    @Test
    void theTransceiverCountIsWhatIsLeftNotWhatIsFitted() {
        CapabilityProfile profile = BoardProfile.of(board());
        assertThat(profile.number("transceivers")).isEqualTo(12);
        assertThat(profile.number("transceivers-fitted")).isEqualTo(16);
    }

    @Test
    void deviceResourcesComeThroughAsCounts() {
        CapabilityProfile profile = BoardProfile.of(board());
        assertThat(profile.number("luts")).isEqualTo(203800);
        assertThat(profile.number("block-ram")).isEqualTo(445);
        assertThat(profile.number("dsp")).isEqualTo(840);
        assertThat(profile.number("flash-mib")).isEqualTo(16);
        assertThat(profile.number("tck-mhz")).isEqualTo(30);
    }

    @Test
    void theBoardIdentityComesThroughAsAttributes() {
        CapabilityProfile profile = BoardProfile.of(board());
        assertThat(profile.attribute("family")).contains("kintex7");
        assertThat(profile.attribute("device")).contains("xc7k325t");
        assertThat(profile.attribute("part")).contains("xc7k325t-2ffg900i");
        assertThat(profile.attribute("model")).contains("rx-carrier-b");
        assertThat(profile.attribute("rack")).contains("rack-2");
        assertThat(profile.attribute("asset")).contains("bx-14");
        assertThat(profile.attribute("vadj")).contains("1800");
    }

    @Test
    void aJobThatAsksForWhatTheBoardHasMatches() {
        assertThat(BoardProfile.matches(board(), wanted("adc-14bit", "transceivers>=4", "family=kintex7")))
                .isTrue();
    }

    @Test
    void aJobThatAsksForSomethingElseDoesNot() {
        assertThat(BoardProfile.matches(board(), wanted("ddr4-sodimm"))).isFalse();
        assertThat(BoardProfile.whyNot(board(), wanted("ddr4-sodimm"))).isEqualTo("no ddr4-sodimm");
    }

    @Test
    void aJobCanAskForABoardWithoutACardInTheWay() {
        assertThat(BoardProfile.matches(board(b -> b.slots(List.of())), wanted("!adc-14bit")))
                .isTrue();
        assertThat(BoardProfile.matches(board(), wanted("!adc-14bit"))).isFalse();
    }

    @Test
    void aJobCanAskForARack() {
        assertThat(BoardProfile.matches(board(), wanted("rack=rack-2"))).isTrue();
        assertThat(BoardProfile.matches(board(), wanted("rack=rack-4"))).isFalse();
    }

    @Test
    void aJobAskingForMoreLanesThanAreFreeIsRefusedWithAReason() {
        assertThat(BoardProfile.whyNot(board(), wanted("transceivers>=14")))
                .isEqualTo("transceivers is 12, wanted >=14");
    }

    @Test
    void everyUnmetRequirementIsReportedAtOnce() {
        String reason =
                BoardProfile.whyNot(board(Board.Builder::noPdu), wanted("power-controlled", "ddr4-sodimm", "jtag"));
        assertThat(reason).isEqualTo("no power-controlled; no ddr4-sodimm");
    }

    @Test
    void aBareBoardStillCarriesTheDeviceFacts() {
        CapabilityProfile profile =
                BoardProfile.of(board(b -> b.slots(List.of()).noAdapter().noPdu()));
        assertThat(profile.tokens()).isEqualTo(Set.of("jtag"));
        assertThat(profile.number("transceivers")).isEqualTo(16);
        assertThat(profile.number("cards")).isZero();
    }
}
