package io.github.rkaushikdev.carrierworks.jtag;

import static io.github.rkaushikdev.carrierworks.jtag.TapState.CAPTURE_DR;
import static io.github.rkaushikdev.carrierworks.jtag.TapState.CAPTURE_IR;
import static io.github.rkaushikdev.carrierworks.jtag.TapState.EXIT1_DR;
import static io.github.rkaushikdev.carrierworks.jtag.TapState.EXIT1_IR;
import static io.github.rkaushikdev.carrierworks.jtag.TapState.EXIT2_DR;
import static io.github.rkaushikdev.carrierworks.jtag.TapState.PAUSE_DR;
import static io.github.rkaushikdev.carrierworks.jtag.TapState.PAUSE_IR;
import static io.github.rkaushikdev.carrierworks.jtag.TapState.RUN_TEST_IDLE;
import static io.github.rkaushikdev.carrierworks.jtag.TapState.SELECT_DR;
import static io.github.rkaushikdev.carrierworks.jtag.TapState.SELECT_IR;
import static io.github.rkaushikdev.carrierworks.jtag.TapState.SHIFT_DR;
import static io.github.rkaushikdev.carrierworks.jtag.TapState.SHIFT_IR;
import static io.github.rkaushikdev.carrierworks.jtag.TapState.TEST_LOGIC_RESET;
import static io.github.rkaushikdev.carrierworks.jtag.TapState.UPDATE_IR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.ValidationError;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class TapTest {

    private static List<String> names() {
        return Arrays.stream(TapState.values()).map(TapState::value).toList();
    }

    @Test
    void allSixteenStatesAreThere() {
        assertThat(TapState.values()).hasSize(16);
        assertThat(names()).contains("shift_ir", "update_dr");
    }

    @Test
    void fiveOnesLandInResetFromAnywhere() {
        for (TapState state : TapState.values()) {
            assertThat(Tap.walk(state, Tap.resetPath())).isEqualTo(TEST_LOGIC_RESET);
        }
        assertThat(Tap.resetPath()).hasSize(Tap.RESET_CLOCKS);
    }

    @Test
    void theHeadlineTransitionsMatchTheStandard() {
        assertThat(TEST_LOGIC_RESET.step(false)).isEqualTo(RUN_TEST_IDLE);
        assertThat(RUN_TEST_IDLE.step(true)).isEqualTo(SELECT_DR);
        assertThat(SELECT_DR.step(true)).isEqualTo(SELECT_IR);
        assertThat(SELECT_IR.step(true)).isEqualTo(TEST_LOGIC_RESET);
        assertThat(CAPTURE_IR.step(false)).isEqualTo(SHIFT_IR);
        assertThat(SHIFT_IR.step(false)).isEqualTo(SHIFT_IR);
        assertThat(SHIFT_IR.step(true)).isEqualTo(EXIT1_IR);
        assertThat(EXIT1_IR.step(true)).isEqualTo(UPDATE_IR);
        assertThat(UPDATE_IR.step(true)).isEqualTo(SELECT_DR);
    }

    @Test
    void thePauseBranchesComeBackToTheShift() {
        assertThat(EXIT1_DR.step(false)).isEqualTo(PAUSE_DR);
        assertThat(PAUSE_DR.step(true)).isEqualTo(EXIT2_DR);
        assertThat(EXIT2_DR.step(false)).isEqualTo(SHIFT_DR);
    }

    @Test
    void onlyFourStatesHoldStill() {
        assertThat(Arrays.stream(TapState.values()).filter(TapState::isStable))
                .containsExactly(TEST_LOGIC_RESET, RUN_TEST_IDLE, PAUSE_DR, PAUSE_IR);
    }

    @Test
    void shiftStatesAreTheTwoThatMoveData() {
        assertThat(SHIFT_DR.isShiftState()).isTrue();
        assertThat(SHIFT_IR.isShiftState()).isTrue();
        assertThat(CAPTURE_DR.isShiftState()).isFalse();
    }

    @Test
    void walkingAStringOfBits() {
        assertThat(Tap.walk(TEST_LOGIC_RESET, "0110")).isEqualTo(CAPTURE_IR);
        assertThat(Tap.walk(RUN_TEST_IDLE, "0011")).isEqualTo(SELECT_IR);
    }

    @Test
    void bitsMayBeWrittenWithSpacing() {
        assertThat(Tap.walk(TEST_LOGIC_RESET, "0 11_0")).isEqualTo(Tap.walk(TEST_LOGIC_RESET, "0110"));
    }

    @Test
    void bitsMayBeAList() {
        assertThat(Tap.walk(TEST_LOGIC_RESET, List.of(0, 1, 1, 0))).isEqualTo(CAPTURE_IR);
    }

    static Stream<Arguments> bitsThatAreNotBitsAreRefused() {
        return Stream.of(Arguments.of("012"), Arguments.of("abc"), Arguments.of(List.of(0, 2)));
    }

    @ParameterizedTest
    @MethodSource
    void bitsThatAreNotBitsAreRefused(Object bits) {
        assertThatThrownBy(() -> {
                    switch (bits) {
                        case String text -> Tap.walk(RUN_TEST_IDLE, text);
                        case List<?> list ->
                            Tap.walk(
                                    RUN_TEST_IDLE,
                                    list.stream().map(Integer.class::cast).toList());
                        default -> throw new IllegalArgumentException("not a run of bits: " + bits);
                    }
                })
                .isInstanceOf(ValidationError.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "shift", "SHIFT_DR", "idle"})
    void statesThatDoNotExistAreRefused(String state) {
        assertThatThrownBy(() -> TapState.parse(state).step(false)).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> TapState.parse(state).isStable()).isInstanceOf(ValidationError.class);
    }

    @Test
    void aPathToWhereYouAlreadyAreIsEmpty() {
        assertThat(Tap.pathBetween(RUN_TEST_IDLE, RUN_TEST_IDLE)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "run_test_idle, shift_ir",
        "run_test_idle, shift_dr",
        "shift_dr, run_test_idle",
        "shift_ir, shift_dr",
        "test_logic_reset, update_dr",
        "pause_ir, shift_dr",
        "update_dr, capture_ir",
    })
    void everySearchedPathActuallyArrives(String start, String end) {
        TapState from = TapState.parse(start);
        TapState to = TapState.parse(end);
        assertThat(Tap.walk(from, Tap.pathBetween(from, to))).isEqualTo(to);
    }

    @Test
    void theCommonPathsAreTheLengthsADriverExpects() {
        assertThat(Tap.pathBetween(RUN_TEST_IDLE, SHIFT_IR)).hasSize(4);
        assertThat(Tap.pathBetween(RUN_TEST_IDLE, SHIFT_DR)).hasSize(3);
        assertThat(Tap.pathBetween(SHIFT_DR, RUN_TEST_IDLE)).hasSize(3);
        assertThat(Tap.pathBetween(TEST_LOGIC_RESET, RUN_TEST_IDLE)).hasSize(1);
    }

    @Test
    void pathsAreFoundBetweenEveryPairOfStates() {
        for (TapState start : TapState.values()) {
            for (TapState end : TapState.values()) {
                assertThat(Tap.walk(start, Tap.pathBetween(start, end))).isEqualTo(end);
            }
        }
    }

    @Test
    void aPathToAStateThatDoesNotExistIsRefused() {
        assertThatThrownBy(() -> Tap.pathBetween(RUN_TEST_IDLE, TapState.parse("shift")))
                .isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> Tap.pathBetween(TapState.parse("nowhere"), RUN_TEST_IDLE))
                .isInstanceOf(ValidationError.class);
    }
}
