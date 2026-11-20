package io.github.rkaushikdev.carrierworks.inventory;

import static io.github.rkaushikdev.carrierworks.inventory.BoardSetup.artix;
import static io.github.rkaushikdev.carrierworks.inventory.BoardSetup.kintex;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.carrierworks.jtag.BitVector;
import io.github.rkaushikdev.carrierworks.jtag.ChainDifference;
import io.github.rkaushikdev.carrierworks.jtag.Discover;
import io.github.rkaushikdev.carrierworks.jtag.ScanChain;
import io.github.rkaushikdev.carrierworks.jtag.TapPosition;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChainsTest {

    private static final long SYSCTL = 0x06E5E093L;

    private static BitVector scanOf(ScanChain chain) {
        return Discover.scanBitsFor(chain);
    }

    private static List<ChainDifference.Kind> kinds(ChainCheck check) {
        return check.differences().stream().map(ChainDifference::kind).toList();
    }

    @Test
    void aPlainCarrierPresentsOneDevice() {
        ScanChain chain = Chains.chainFor(artix());
        assertThat(chain.size()).isEqualTo(1);
        assertThat(chain.taps().get(0).device()).contains("xc7a100t");
    }

    @Test
    void aCarrierWithAControllerPresentsItFirst() {
        ScanChain chain = Chains.chainFor(kintex());
        assertThat(chain.labels()).containsExactly("sysctl", "u1");
        assertThat(chain.taps().get(0).expectedIdcode()).contains(SYSCTL);
        assertThat(chain.taps().get(0).irLength()).isEqualTo(8);
        assertThat(chain.taps().get(1).device()).contains("xc7k325t");
    }

    @Test
    void theControllerWidthsAddIntoTheInstructionRegister() {
        assertThat(Chains.chainFor(kintex()).irLength()).isEqualTo(14);
    }

    @Test
    void theCarrierTableIsTheOnlyPlaceControllersAreNamed() {
        assertThat(Chains.CARRIER_TAPS).containsKey("rx-carrier-b");
        assertThat(Chains.CARRIER_TAPS).doesNotContainKey("tx-carrier-a");
    }

    @Test
    void aHealthyScanReconciles() {
        Board board = kintex();
        ChainCheck check = Chains.reconcile(board, scanOf(Chains.chainFor(board)));
        assertThat(check.ok()).isTrue();
        assertThat(check.faultKind()).isEmpty();
        assertThat(check.nextState()).isEmpty();
        assertThat(check.describe()).startsWith("bx-14 chain matches");
    }

    @Test
    void aHealthyScanOfAPlainCarrierReconcilesToo() {
        Board board = artix();
        assertThat(Chains.reconcile(board, scanOf(Chains.chainFor(board))).ok()).isTrue();
    }

    @Test
    void anOpenChainIsAFaultWorthRetrying() {
        ChainCheck check = Chains.reconcile(kintex(), BitVector.ones(96));
        assertThat(check.ok()).isFalse();
        assertThat(check.retryWorthwhile()).isTrue();
        assertThat(check.faultKind()).contains("chain");
        assertThat(check.nextState()).contains(BoardStatus.FAULTED);
        assertThat(check.describe()).contains("open chain");
    }

    @Test
    void aBoardWithNoPowerReadsAsAFault() {
        ChainCheck check = Chains.reconcile(kintex(), BitVector.zeros(96));
        assertThat(check.retryWorthwhile()).isTrue();
        assertThat(check.describe()).contains("no power");
    }

    @Test
    void theWrongPartFittedSendsTheBoardToMaintenance() {
        Board board = kintex();
        ScanChain wrong =
                new ScanChain(List.of(new TapPosition("sysctl", 8, SYSCTL), TapPosition.forDevice("u1", "xc7k70t")));
        ChainCheck check = Chains.reconcile(board, scanOf(wrong));
        assertThat(check.ok()).isFalse();
        assertThat(check.retryWorthwhile()).isFalse();
        assertThat(check.nextState()).contains(BoardStatus.MAINTENANCE);
        assertThat(check.differences().get(0).kind()).isEqualTo(ChainDifference.Kind.DEVICE);
        assertThat(check.differences().get(0).position()).contains(1);
    }

    @Test
    void aMissingControllerShowsUpAsALengthDifference() {
        Board board = kintex();
        ScanChain without = new ScanChain(List.of(TapPosition.forDevice("u1", "xc7k325t")));
        ChainCheck check = Chains.reconcile(board, scanOf(without));
        assertThat(kinds(check)).contains(ChainDifference.Kind.LENGTH);
    }

    @Test
    void aChainPluggedInBackwardsIsReportedAsOrder() {
        Board board = kintex();
        ScanChain swapped =
                new ScanChain(List.of(TapPosition.forDevice("u1", "xc7k325t"), new TapPosition("sysctl", 8, SYSCTL)));
        ChainCheck check = Chains.reconcile(board, scanOf(swapped));
        assertThat(kinds(check)).containsExactly(ChainDifference.Kind.ORDER, ChainDifference.Kind.ORDER);
    }

    @Test
    void theDifferenceNamesWhatItExpectedAndWhatAnswered() {
        Board board = kintex();
        ScanChain wrong =
                new ScanChain(List.of(new TapPosition("sysctl", 8, SYSCTL), TapPosition.forDevice("u1", "xc7a35t")));
        ChainDifference difference =
                Chains.reconcile(board, scanOf(wrong)).differences().get(0);
        assertThat(difference.expected()).isEqualTo("xc7k325t");
        assertThat(difference.found()).isEqualTo("xc7a35t");
    }

    @Test
    void theCheckCarriesWhatAnsweredForTheLog() {
        Board board = kintex();
        ChainCheck check = Chains.reconcile(board, scanOf(Chains.chainFor(board)));
        assertThat(check.found()).contains("xc7k325t");
        assertThat(check.found()).contains("0x06e5e093");
    }

    @Test
    void aScanThatStopsPartWayIsAWiringStory() {
        ChainCheck check = Chains.reconcile(kintex(), new BitVector(0x03631093, 32).slice(0, 20));
        assertThat(check.retryWorthwhile()).isTrue();
        assertThat(check.ok()).isFalse();
    }
}
