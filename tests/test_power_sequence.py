import pytest

from fpga.errors import ValidationError
from fpga.power.rails import tree_for_model
from fpga.power.sequence import (
    Interlock,
    PowerStep,
    interlocks_from,
    power_down_plan,
    power_up_plan,
)


def tree():
    return tree_for_model("tx-carrier-a")


def test_a_power_up_enables_settles_and_checks_every_rail():
    plan = power_up_plan(tree())
    assert len(plan.steps_of("enable")) == 4
    assert len(plan.steps_of("check")) == 4
    assert plan.rails() == ("vccint", "vccaux", "vcco33", "vadj")
    assert plan.runnable


def test_the_first_rail_up_is_the_core():
    plan = power_up_plan(tree())
    assert plan.steps[0] == PowerStep(action="enable", rail="vccint")


def test_a_check_follows_the_settle_for_each_rail():
    plan = power_up_plan(tree())
    kinds = [step.action for step in plan.steps[:3]]
    assert kinds == ["enable", "settle", "check"]


def test_verification_can_be_left_out_for_a_board_with_no_monitors():
    plan = power_up_plan(tree(), verify=False)
    assert plan.steps_of("check") == ()
    assert len(plan.steps_of("enable")) == 4


def test_the_settle_times_come_from_the_tree():
    plan = power_up_plan(tree())
    assert plan.total_ms == tree().total_settle_ms


def test_powering_down_runs_the_other_way():
    plan = power_down_plan(tree())
    rails = [step.rail for step in plan.steps_of("disable")]
    assert rails == ["vadj", "vcco33", "vccaux", "vccint"]


def test_powering_down_a_transceiver_board_takes_the_analogue_rails_first():
    plan = power_down_plan(tree_for_model("rx-carrier-b"))
    rails = [step.rail for step in plan.steps_of("disable")]
    assert rails[:2] == ["mgtavtt", "mgtavcc"]


def test_an_engaged_interlock_stops_the_whole_sequence():
    locks = (Interlock(name="lid", engaged=True, reason="rack door open"),)
    plan = power_up_plan(tree(), interlocks=locks)
    assert not plan.runnable
    assert plan.blockers == ("lid: rack door open",)
    assert plan.describe() == "tx-carrier-a up blocked: lid: rack door open"


def test_a_clear_interlock_lets_it_run():
    locks = (Interlock(name="lid", engaged=False),)
    assert power_up_plan(tree(), interlocks=locks).runnable


def test_interlocks_apply_to_powering_down_as_well():
    locks = (Interlock(name="maintenance", engaged=True, reason="hands inside"),)
    assert not power_down_plan(tree(), interlocks=locks).runnable


def test_every_engaged_interlock_is_listed():
    locks = (
        Interlock(name="lid", engaged=True),
        Interlock(name="thermal", engaged=True, reason="die at 96C"),
        Interlock(name="maintenance", engaged=False),
    )
    plan = power_up_plan(tree(), interlocks=locks)
    assert plan.blockers == ("lid", "thermal: die at 96C")


def test_a_runnable_plan_describes_its_size():
    described = power_up_plan(tree()).describe()
    assert described.startswith("tx-carrier-a up: 12 steps")


def test_an_interlock_that_cannot_be_read_counts_as_engaged():
    def unreadable():
        raise OSError("controller rebooting")

    locks = interlocks_from({"lid": unreadable})
    assert locks[0].engaged
    assert locks[0].reason == "could not be read"


def test_interlocks_are_built_from_their_checks():
    locks = interlocks_from(
        {"lid": lambda: False, "thermal": lambda: True},
        {"thermal": "die at 96C"},
    )
    engaged = [lock.name for lock in locks if lock.engaged]
    assert engaged == ["thermal"]
    assert locks[1].reason == "die at 96C"


def test_a_step_nobody_has_defined_is_refused():
    with pytest.raises(ValidationError):
        PowerStep(action="wiggle", rail="vccint")
    with pytest.raises(ValidationError):
        PowerStep(action="settle", rail="vccint", millis=-1)


def test_a_step_describes_itself():
    assert PowerStep(action="enable", rail="vccint").describe() == "enable vccint"
    assert PowerStep(action="settle", rail="vccint", millis=8).describe() == "settle vccint (8ms)"
