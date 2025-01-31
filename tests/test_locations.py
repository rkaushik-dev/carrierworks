import pytest

from fpga.errors import ValidationError
from fpga.locations import BenchLocation, PduChannel, downwind_of


@pytest.mark.parametrize(
    "text",
    [
        "rack-2/shelf-04/slot-3",
        "rack-2:4:3",
        "rack-2/4/3",
        " RACK-2 / SHELF-4 / SLOT-3 ",
    ],
)
def test_locations_parse_however_they_were_typed(text):
    assert BenchLocation.parse(text) == BenchLocation("rack-2", 4, 3)


def test_locations_print_padded_and_labelled():
    assert str(BenchLocation("rack-2", 4, 3)) == "rack-2/shelf-04/slot-3"


@pytest.mark.parametrize(
    "text", ["", "rack-2", "rack-2/13/1", "rack-2/4/7", "rack-2/0/1", "2/4/3x"]
)
def test_bad_locations_are_refused(text):
    with pytest.raises(ValidationError):
        BenchLocation.parse(text)


def test_rack_names_are_normalised_on_the_way_in():
    assert BenchLocation("Rack 2", 4, 3).rack == "rack-2"


def test_locations_sort_rack_then_shelf_then_slot():
    locations = [
        BenchLocation("rack-2", 4, 3),
        BenchLocation("rack-1", 12, 6),
        BenchLocation("rack-2", 4, 1),
        BenchLocation("rack-2", 1, 6),
    ]
    assert [str(loc) for loc in sorted(locations)] == [
        "rack-1/shelf-12/slot-6",
        "rack-2/shelf-01/slot-6",
        "rack-2/shelf-04/slot-1",
        "rack-2/shelf-04/slot-3",
    ]


def test_same_shelf_ignores_the_slot():
    assert BenchLocation("rack-2", 4, 3).same_shelf(BenchLocation("rack-2", 4, 6))
    assert not BenchLocation("rack-2", 4, 3).same_shelf(BenchLocation("rack-3", 4, 3))


def test_above_is_about_height_not_distance():
    high = BenchLocation("rack-2", 9, 1)
    low = BenchLocation("rack-2", 4, 1)
    assert high.is_above(low)
    assert not low.is_above(high)
    assert not high.is_above(BenchLocation("rack-3", 4, 1))


def test_shelves_above_stop_at_the_top_of_the_rack():
    assert BenchLocation("rack-2", 11, 3).shelves_above(4) == (BenchLocation("rack-2", 12, 3),)
    assert BenchLocation("rack-2", 12, 3).shelves_above(2) == ()


def test_asking_for_no_shelves_gives_none():
    assert BenchLocation("rack-2", 4, 3).shelves_above(0) == ()
    with pytest.raises(ValidationError):
        BenchLocation("rack-2", 4, 3).shelves_above(-1)


def test_downwind_walks_up_the_column_nearest_first():
    source = BenchLocation("rack-2", 4, 3)
    candidates = {
        "bx-1": BenchLocation("rack-2", 6, 3),
        "bx-2": BenchLocation("rack-2", 5, 3),
        "bx-3": BenchLocation("rack-2", 3, 3),
        "bx-4": BenchLocation("rack-2", 5, 4),
        "bx-5": BenchLocation("rack-3", 5, 3),
    }
    assert downwind_of(source, candidates) == ("bx-2", "bx-1")


def test_reach_limits_how_far_the_warning_carries():
    source = BenchLocation("rack-2", 4, 3)
    candidates = {"bx-1": BenchLocation("rack-2", 6, 3)}
    assert downwind_of(source, candidates, reach=1) == ()
    assert downwind_of(source, candidates, reach=2) == ("bx-1",)


def test_nothing_downwind_of_an_empty_rack():
    assert downwind_of(BenchLocation("rack-2", 4, 3), {}) == ()


def test_pdu_channels_parse_and_print():
    assert PduChannel.parse("pdu-a/outlet-7") == PduChannel("pdu-a", 7)
    assert PduChannel.parse("pdu-a:7") == PduChannel("pdu-a", 7)
    assert str(PduChannel("pdu-a", 7)) == "pdu-a/outlet-07"


@pytest.mark.parametrize("text", ["", "pdu-a", "pdu-a/0", "pdu-a/25", "pdu-a/outlet"])
def test_bad_pdu_channels_are_refused(text):
    with pytest.raises(ValidationError):
        PduChannel.parse(text)


def test_a_board_and_its_outlet_are_separate_addresses():
    location = BenchLocation("rack-2", 4, 3)
    channel = PduChannel("pdu-c", 11)
    assert location.rack != channel.pdu
