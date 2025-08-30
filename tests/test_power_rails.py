import pytest

from fpga.electrical import RailSpec
from fpga.errors import ConfigurationError, NotFound
from fpga.power.rails import PowerTree, RailStep, tree_for_model


def rail(name, millivolts=1000, current=1000):
    return RailSpec(
        name=name,
        nominal_mv=millivolts,
        tolerance_percent=5,
        max_current_ma=current,
    )


def test_a_carrier_has_a_tree():
    tree = tree_for_model("rx-carrier-b")
    assert "vccint" in tree.names()
    assert tree.step("vadj").rail.nominal_mv == 1800


def test_a_carrier_nobody_has_described_is_a_not_found():
    with pytest.raises(NotFound):
        tree_for_model("hil-rig-1")


def test_the_core_comes_up_before_everything_that_needs_it():
    order = [step.name for step in tree_for_model("rx-carrier-b").order_up()]
    assert order[0] == "vccint"
    assert order.index("vccaux") < order.index("vcco33")
    assert order.index("vccaux") < order.index("vadj")
    assert order.index("mgtavcc") < order.index("mgtavtt")


def test_the_order_is_the_same_every_time():
    tree = tree_for_model("rx-carrier-b")
    assert tree.order_up() == tree.order_up()


def test_powering_down_takes_the_analogue_rails_first():
    order = [step.name for step in tree_for_model("rx-carrier-b").order_down()]
    assert order[0] == "mgtavtt"
    assert order[1] == "mgtavcc"
    assert order[-1] == "vccint"


def test_a_carrier_without_transceiver_rails_just_reverses():
    tree = tree_for_model("tx-carrier-a")
    up = [step.name for step in tree.order_up()]
    down = [step.name for step in tree.order_down()]
    assert down == list(reversed(up))


def test_settle_times_add_up():
    assert tree_for_model("tx-carrier-a").total_settle_ms == 8 + 5 + 5 + 5


def test_a_tree_describes_its_order():
    described = tree_for_model("tx-carrier-a").describe()
    assert described.startswith("tx-carrier-a: vccint -> vccaux")


def test_a_rail_nobody_declared_is_a_not_found():
    with pytest.raises(NotFound):
        tree_for_model("tx-carrier-a").step("mgtavcc")


def test_a_tree_with_no_rails_is_refused():
    with pytest.raises(ConfigurationError):
        PowerTree(model="empty", steps=())


def test_two_rails_cannot_share_a_name():
    with pytest.raises(ConfigurationError):
        PowerTree(
            model="doubled",
            steps=(RailStep(rail("vccint")), RailStep(rail("vccint", 1800))),
        )


def test_a_dependency_on_a_rail_that_is_not_there_is_refused():
    with pytest.raises(ConfigurationError) as caught:
        PowerTree(
            model="hopeful",
            steps=(RailStep(rail("vccaux"), depends_on=("vccint",)),),
        )
    assert caught.value.details["missing"] == "vccint"


def test_rails_that_depend_on_each_other_are_refused():
    with pytest.raises(ConfigurationError) as caught:
        PowerTree(
            model="knotted",
            steps=(
                RailStep(rail("first"), depends_on=("second",)),
                RailStep(rail("second"), depends_on=("first",)),
            ),
        )
    assert "depend on each other" in caught.value.message


def test_a_negative_settle_time_is_refused():
    with pytest.raises(ConfigurationError):
        RailStep(rail("vccint"), settle_ms=-1)


def test_dependencies_are_normalised_on_the_way_in():
    step = RailStep(rail("vccaux"), depends_on=("VCC INT",))
    assert step.depends_on == ("vcc-int",)


def test_a_deep_chain_orders_from_the_bottom_up():
    tree = PowerTree(
        model="stack",
        steps=(
            RailStep(rail("d"), depends_on=("c",)),
            RailStep(rail("c"), depends_on=("b",)),
            RailStep(rail("b"), depends_on=("a",)),
            RailStep(rail("a")),
        ),
    )
    assert [step.name for step in tree.order_up()] == ["a", "b", "c", "d"]


def test_independent_rails_keep_their_declaration_order():
    tree = PowerTree(
        model="parallel",
        steps=(RailStep(rail("b")), RailStep(rail("a")), RailStep(rail("c"))),
    )
    assert [step.name for step in tree.order_up()] == ["b", "a", "c"]
