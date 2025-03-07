import pytest

from fpga.errors import ValidationError
from fpga.jtag.tap import (
    RESET_CLOCKS,
    STATES,
    is_shift_state,
    is_stable,
    path_between,
    reset_path,
    step,
    walk,
)


def test_all_sixteen_states_are_there():
    assert len(STATES) == 16
    assert "shift_ir" in STATES
    assert "update_dr" in STATES


def test_five_ones_land_in_reset_from_anywhere():
    for state in STATES:
        assert walk(state, reset_path()) == "test_logic_reset"
    assert len(reset_path()) == RESET_CLOCKS


def test_the_headline_transitions_match_the_standard():
    assert step("test_logic_reset", 0) == "run_test_idle"
    assert step("run_test_idle", 1) == "select_dr"
    assert step("select_dr", 1) == "select_ir"
    assert step("select_ir", 1) == "test_logic_reset"
    assert step("capture_ir", 0) == "shift_ir"
    assert step("shift_ir", 0) == "shift_ir"
    assert step("shift_ir", 1) == "exit1_ir"
    assert step("exit1_ir", 1) == "update_ir"
    assert step("update_ir", 1) == "select_dr"


def test_the_pause_branches_come_back_to_the_shift():
    assert step("exit1_dr", 0) == "pause_dr"
    assert step("pause_dr", 1) == "exit2_dr"
    assert step("exit2_dr", 0) == "shift_dr"


def test_only_four_states_hold_still():
    stable = [state for state in STATES if is_stable(state)]
    assert stable == ["test_logic_reset", "run_test_idle", "pause_dr", "pause_ir"]


def test_shift_states_are_the_two_that_move_data():
    assert is_shift_state("shift_dr")
    assert is_shift_state("shift_ir")
    assert not is_shift_state("capture_dr")


def test_walking_a_string_of_bits():
    assert walk("test_logic_reset", "0110") == "capture_ir"
    assert walk("run_test_idle", "0011") == "select_ir"


def test_bits_may_be_written_with_spacing():
    assert walk("test_logic_reset", "0 11_0") == walk("test_logic_reset", "0110")


def test_bits_may_be_a_list():
    assert walk("test_logic_reset", [0, 1, 1, 0]) == "capture_ir"


@pytest.mark.parametrize("bits", ["012", "abc", [0, 2]])
def test_bits_that_are_not_bits_are_refused(bits):
    with pytest.raises(ValidationError):
        walk("run_test_idle", bits)


@pytest.mark.parametrize("state", ["", "shift", "SHIFT_DR", "idle"])
def test_states_that_do_not_exist_are_refused(state):
    with pytest.raises(ValidationError):
        step(state, 0)
    with pytest.raises(ValidationError):
        is_stable(state)


def test_a_path_to_where_you_already_are_is_empty():
    assert path_between("run_test_idle", "run_test_idle") == ()


@pytest.mark.parametrize(
    ("start", "end"),
    [
        ("run_test_idle", "shift_ir"),
        ("run_test_idle", "shift_dr"),
        ("shift_dr", "run_test_idle"),
        ("shift_ir", "shift_dr"),
        ("test_logic_reset", "update_dr"),
        ("pause_ir", "shift_dr"),
        ("update_dr", "capture_ir"),
    ],
)
def test_every_searched_path_actually_arrives(start, end):
    assert walk(start, list(path_between(start, end))) == end


def test_the_common_paths_are_the_lengths_a_driver_expects():
    assert len(path_between("run_test_idle", "shift_ir")) == 4
    assert len(path_between("run_test_idle", "shift_dr")) == 3
    assert len(path_between("shift_dr", "run_test_idle")) == 3
    assert len(path_between("test_logic_reset", "run_test_idle")) == 1


def test_paths_are_found_between_every_pair_of_states():
    for start in STATES:
        for end in STATES:
            assert walk(start, list(path_between(start, end))) == end


def test_a_path_to_a_state_that_does_not_exist_is_refused():
    with pytest.raises(ValidationError):
        path_between("run_test_idle", "shift")
    with pytest.raises(ValidationError):
        path_between("nowhere", "run_test_idle")
