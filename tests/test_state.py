from datetime import UTC, datetime, timedelta

import pytest

from fpga.errors import ValidationError
from fpga.inventory.state import STATES, BoardState, allowed_moves, can_move

WHEN = datetime(2025, 7, 9, 14, 0, tzinfo=UTC)


def state(name="available", reason="", when=WHEN):
    return BoardState(state=name, since=when, reason=reason)


def test_the_seven_states_are_the_ones_on_the_whiteboard():
    assert STATES == (
        "available",
        "leased",
        "programming",
        "faulted",
        "quarantined",
        "maintenance",
        "retired",
    )


def test_a_board_goes_from_available_to_leased_and_back():
    live = state()
    leased = live.move("leased", WHEN + timedelta(minutes=1))
    assert leased.state == "leased"
    assert leased.move("available", WHEN + timedelta(hours=1)).state == "available"


def test_programming_only_happens_while_a_board_is_leased():
    assert can_move("leased", "programming")
    assert not can_move("available", "programming")


def test_a_refusal_names_what_was_allowed_instead():
    with pytest.raises(ValidationError) as caught:
        state().move("programming", WHEN)
    assert caught.value.details["allowed"] == "leased, faulted, maintenance, retired"


def test_a_quarantined_board_cannot_go_straight_back_into_service():
    quarantined = state("quarantined", reason="three chain faults this week")
    assert not can_move("quarantined", "available")
    assert quarantined.moves() == ("maintenance", "retired")


def test_a_quarantined_board_comes_back_through_maintenance():
    quarantined = state("quarantined", reason="three chain faults this week")
    fixed = quarantined.move("maintenance", WHEN + timedelta(days=1), reason="reflowed the header")
    assert fixed.move("available", WHEN + timedelta(days=2)).usable


def test_retired_is_the_end():
    retired = state("retired", reason="device lifted for spares")
    assert retired.terminal
    assert retired.moves() == ()
    with pytest.raises(ValidationError):
        retired.move("available", WHEN + timedelta(days=1))


def test_a_faulted_board_can_come_back_without_maintenance():
    faulted = state("faulted", reason="chain read as all ones")
    assert faulted.move("available", WHEN + timedelta(minutes=5)).usable


def test_the_states_that_take_a_board_out_of_service_are_named():
    assert state("faulted", reason="x").out_of_service
    assert state("quarantined", reason="x").out_of_service
    assert state("maintenance", reason="x").out_of_service
    assert state("retired", reason="x").out_of_service
    assert not state().out_of_service


def test_busy_covers_leased_and_programming():
    assert state("leased").busy
    assert state("programming").busy
    assert not state().busy
    assert not state("faulted", reason="x").busy


def test_only_available_is_usable():
    for name in STATES:
        reason = "" if name in ("available", "leased", "programming") else "because"
        assert BoardState(state=name, since=WHEN, reason=reason).usable == (name == "available")


def test_taking_a_board_out_of_service_needs_a_reason():
    with pytest.raises(ValidationError):
        BoardState(state="faulted", since=WHEN)
    with pytest.raises(ValidationError):
        BoardState(state="quarantined", since=WHEN, reason="   ")


def test_ordinary_states_do_not_need_one():
    assert BoardState(state="leased", since=WHEN).reason == ""


def test_a_state_nobody_has_is_refused():
    with pytest.raises(ValidationError):
        BoardState(state="on-fire", since=WHEN)
    with pytest.raises(ValidationError):
        allowed_moves("on-fire")


def test_timestamps_have_to_be_in_utc():
    with pytest.raises(ValidationError):
        BoardState(state="available", since=datetime(2025, 7, 9, 14, 0))


def test_a_board_cannot_change_state_backwards_in_time():
    with pytest.raises(ValidationError):
        state().move("leased", WHEN - timedelta(minutes=1))


def test_how_long_a_board_has_been_where_it_is():
    assert state().held_seconds(WHEN + timedelta(minutes=30)) == 1800
    with pytest.raises(ValidationError):
        state().held_seconds(WHEN - timedelta(seconds=1))


def test_a_state_describes_itself_with_its_reason_and_who_did_it():
    faulted = BoardState(
        state="faulted",
        since=WHEN,
        reason="chain read as all ones",
        actor="nightly",
    )
    assert faulted.describe() == (
        "faulted since 2025-07-09T14:00:00+00:00: chain read as all ones (nightly)"
    )
    assert state().describe() == "available since 2025-07-09T14:00:00+00:00"


def test_every_state_that_is_not_terminal_has_somewhere_to_go():
    for name in STATES:
        moves = allowed_moves(name)
        assert (moves == ()) == (name == "retired")
        assert all(move in STATES for move in moves)
