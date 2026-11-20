package io.github.rkaushikdev.carrierworks.jtag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.ConfigurationError;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChainTest {

    private static ScanChain twoPartChain() {
        return ScanChain.ofDevices("xc7a100t", "xc7k325t");
    }

    private static ScanChain mixedChain() {
        return new ScanChain(List.of(
                TapPosition.forDevice("u1", "xc7a100t"),
                new TapPosition("u2", 4),
                TapPosition.forDevice("u3", "xc7s25")));
    }

    @Test
    void aPositionFromTheCatalogCarriesWidthAndIdcode() {
        TapPosition tap = TapPosition.forDevice("u1", "XC7A100T");
        assertThat(tap.irLength()).isEqualTo(6);
        assertThat(tap.expectedIdcode()).contains(0x03631093L);
        assertThat(tap.device()).contains("xc7a100t");
        assertThat(tap.known()).isTrue();
    }

    @Test
    void aPositionForAnUnknownPartStillNeedsAWidth() {
        TapPosition tap = new TapPosition("U2", 4);
        assertThat(tap.label()).isEqualTo("u2");
        assertThat(tap.known()).isFalse();
        assertThat(tap.expectedIdcode()).isEmpty();
    }

    @Test
    void aPositionForADeviceNobodyStocksIsANotFound() {
        assertThatThrownBy(() -> TapPosition.forDevice("u1", "xc7v2000t")).isInstanceOf(NotFound.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 33, -6})
    void anImplausibleInstructionRegisterIsRefused(int width) {
        assertThatThrownBy(() -> new TapPosition("u1", width)).isInstanceOf(ValidationError.class);
    }

    @Test
    void anIdcodeThatDoesNotFitIsRefused() {
        assertThatThrownBy(() -> new TapPosition("u1", 6, 0x1_0000_0000L)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aChainNeedsAtLeastOneDevice() {
        assertThatThrownBy(() -> new ScanChain(List.of())).isInstanceOf(ConfigurationError.class);
    }

    @Test
    void twoPositionsCannotShareALabel() {
        assertThatThrownBy(() -> new ScanChain(List.of(new TapPosition("u1", 6), new TapPosition("u1", 6))))
                .isInstanceOf(ConfigurationError.class);
    }

    @Test
    void theChainTotalsItsInstructionRegisters() {
        assertThat(twoPartChain().irLength()).isEqualTo(12);
        assertThat(mixedChain().irLength()).isEqualTo(16);
    }

    @Test
    void positionsAreFoundByLabel() {
        ScanChain chain = twoPartChain();
        assertThat(chain.indexOf("u2")).isEqualTo(1);
        assertThat(chain.tap("u1").device()).contains("xc7a100t");
        assertThatThrownBy(() -> chain.indexOf("u9")).isInstanceOf(NotFound.class);
    }

    @Test
    void indexingOffTheEndIsRefused() {
        assertThatThrownBy(() -> twoPartChain().at(2)).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> twoPartChain().at(-1)).isInstanceOf(ValidationError.class);
    }

    @Test
    void theLastListedDeviceIsTheOneNearestTdo() {
        ScanChain chain = mixedChain();
        assertThat(chain.devicesBeforeTdo(2)).isZero();
        assertThat(chain.devicesBeforeTdo(0)).isEqualTo(2);
        assertThat(chain.devicesAfterTdo(0)).isZero();
        assertThat(chain.devicesAfterTdo(2)).isEqualTo(2);
    }

    @Test
    void instructionBitsSplitAroundAPosition() {
        ScanChain chain = mixedChain();
        int index = chain.indexOf("u2");
        assertThat(chain.irBitsBefore(index)).isEqualTo(6);
        assertThat(chain.irBitsAfter(index)).isEqualTo(6);
        assertThat(chain.irBitsBefore(index) + chain.irBitsAfter(index) + 4).isEqualTo(chain.irLength());
    }

    @Test
    void theFirstPositionHasNothingOnItsTdiSide() {
        ScanChain chain = twoPartChain();
        assertThat(chain.irBitsAfter(0)).isZero();
        assertThat(chain.irBitsBefore(1)).isZero();
    }

    @Test
    void tdoOrderIsTheReverseOfTheListedOrder() {
        ScanChain chain = mixedChain();
        assertThat(chain.inTdoOrder().stream().map(TapPosition::label)).containsExactly("u3", "u2", "u1");
        assertThat(chain.labels()).containsExactly("u1", "u2", "u3");
    }

    @Test
    void aChainDescribesItselfForTheLog() {
        assertThat(twoPartChain().describe()).isEqualTo("u1:xc7a100t/ir6 -> u2:xc7k325t/ir6");
        assertThat(mixedChain().describe()).contains("unknown");
    }

    @Test
    void buildingFromDevicesLabelsThemInOrder() {
        ScanChain chain = ScanChain.ofDevices("xc7a35t", "xc7a35t", "xc7s25");
        assertThat(chain.labels()).containsExactly("u1", "u2", "u3");
        assertThat(chain.size()).isEqualTo(3);
    }
}
