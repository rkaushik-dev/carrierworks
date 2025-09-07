import pytest

from boardsetup import kintex
from fpga.errors import PowerFault, ValidationError
from fpga.locations import PduChannel
from fpga.power.pdu import MINIMUM_OFF_MS, FakePdu, PduDriver, PowerController


def board(**rest):
    return kintex("bx-14", **rest)


def controller(**rest):
    return PowerController(FakePdu(), **rest)


def test_the_fake_satisfies_the_driver_protocol():
    assert isinstance(FakePdu(), PduDriver)


def test_a_board_starts_off_and_can_be_switched_on():
    live = controller()
    assert not live.is_on(board())
    assert live.power_on(board())
    assert live.is_on(board())


def test_switching_on_a_board_that_is_on_changes_nothing():
    live = controller()
    live.power_on(board())
    assert not live.power_on(board())


def test_switching_off_works_the_same_way():
    live = controller()
    live.power_on(board())
    assert live.power_off(board())
    assert not live.power_off(board())
    assert not live.is_on(board())


def test_only_the_board_s_own_outlet_is_touched():
    driver = FakePdu()
    live = PowerController(driver)
    live.power_on(board())
    assert driver.commands == ["on pdu-a/outlet-07"]


def test_a_board_with_no_outlet_cannot_be_switched():
    live = controller()
    with pytest.raises(ValidationError) as caught:
        live.power_on(board(outlet=None))
    assert caught.value.details["board"] == "bx-14"


def test_a_cycle_goes_off_then_on():
    driver = FakePdu()
    live = PowerController(driver)
    live.power_on(board())
    driver.commands.clear()
    live.cycle(board())
    assert driver.commands == ["off pdu-a/outlet-07", "on pdu-a/outlet-07"]
    assert live.is_on(board())


def test_a_cycle_holds_the_board_off_long_enough_to_reset_it():
    live = controller()
    assert live.cycle(board()) == MINIMUM_OFF_MS
    assert live.cycle(board(), off_ms=50) == MINIMUM_OFF_MS
    assert live.cycle(board(), off_ms=2000) == 2000


def test_a_controller_with_too_short_a_minimum_is_refused():
    with pytest.raises(ValidationError):
        PowerController(FakePdu(), minimum_off_ms=10)


def test_a_unit_that_does_not_answer_is_a_retryable_fault():
    driver = FakePdu(unreachable={"pdu-a"})
    live = PowerController(driver)
    with pytest.raises(PowerFault) as caught:
        live.power_on(board())
    assert caught.value.retryable


def test_current_is_zero_while_a_board_is_off():
    live = controller()
    assert live.draw_ma(board()) == 0


def test_current_comes_back_once_a_board_is_on():
    driver = FakePdu(currents={"pdu-a/outlet-07": 1400})
    live = PowerController(driver)
    live.power_on(board())
    assert live.draw_ma(board()) == 1400


def test_an_ordinary_draw_is_inside_the_budget():
    driver = FakePdu(currents={"pdu-a/outlet-07": 900})
    live = PowerController(driver)
    live.power_on(board())
    assert not live.over_budget(board())


def test_a_board_pulling_far_more_than_its_cards_account_for_is_flagged():
    driver = FakePdu(currents={"pdu-a/outlet-07": 4000})
    live = PowerController(driver)
    live.power_on(board())
    assert live.over_budget(board())


def test_the_headroom_is_settable_for_a_carrier_that_runs_warm():
    driver = FakePdu(currents={"pdu-a/outlet-07": 1500})
    live = PowerController(driver)
    live.power_on(board())
    assert live.over_budget(board(), headroom_mw=100)
    assert not live.over_budget(board(), headroom_mw=12000)


def test_two_boards_on_one_unit_are_switched_independently():
    driver = FakePdu()
    live = PowerController(driver)
    first = board()
    second = kintex("bx-15", location="rack-2/shelf-05/slot-1", serial=None, outlet=8)
    live.power_on(first)
    assert live.is_on(first)
    assert not live.is_on(second)
    assert driver.state(PduChannel("pdu-a", 7))
    assert not driver.state(PduChannel("pdu-a", 8))
