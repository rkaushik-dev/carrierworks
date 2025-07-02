import pytest

from fpga.errors import ConfigurationError, NotFound, ValidationError
from fpga.inventory.boards import Board, BoardSlot, BoardSummary
from fpga.jtag.adapters import AdapterUnit


def board(**changes):
    fields = {
        "asset": "bx-14",
        "model": "rx-carrier-b",
        "part": "XC7K325T-2FFG900I",
        "flash": "n25q128a13",
        "location": "rack-2/shelf-04/slot-3",
        "slots": (
            BoardSlot("fmc0", "fmc-hpc", "fmc-adc-4ch-14b"),
            BoardSlot("fmc1", "fmc-lpc"),
        ),
        "adapter": AdapterUnit(serial="210308AB1234", model="jtag-hs2"),
        "pdu": None,
    }
    fields.update(changes)
    return Board.build(**fields)


def test_a_board_carries_its_own_facts():
    live = board()
    assert live.tag == "bx-14"
    assert live.device().device == "xc7k325t"
    assert live.flash_spec().capacity_bytes == 16 * 1024 * 1024
    assert live.model == "rx-carrier-b"
    assert live.revision == "a"


def test_a_slot_holds_a_card_that_fits_it():
    live = board()
    assert live.slot("fmc0").fitted == "fmc-adc-4ch-14b"
    assert live.slot("fmc1").empty
    assert [spec.name for spec in live.fitted_cards()] == ["fmc-adc-4ch-14b"]


def test_a_card_that_does_not_fit_its_connector_is_refused():
    with pytest.raises(ConfigurationError):
        BoardSlot("fmc1", "fmc-lpc", "fmc-sfp-quad")


def test_a_low_pin_count_card_goes_in_a_high_pin_count_slot():
    slot = BoardSlot("fmc0", "fmc-hpc", "fmc-adc-2ch-16b")
    assert slot.fitted == "fmc-adc-2ch-16b"


def test_a_slot_nobody_named_is_a_not_found():
    with pytest.raises(NotFound):
        board().slot("fmc9")


def test_two_slots_cannot_share_a_name():
    with pytest.raises(ConfigurationError):
        board(slots=(BoardSlot("fmc0", "fmc-hpc"), BoardSlot("fmc0", "fmc-lpc")))


def test_cards_cannot_want_more_transceivers_than_the_device_has():
    with pytest.raises(ConfigurationError) as caught:
        board(
            part="XC7A35T-2CSG324I",
            slots=(
                BoardSlot("fmc0", "fmc-hpc", "fmc-adc-4ch-14b"),
                BoardSlot("fmc1", "fmc-hpc", "fmc-sfp-quad"),
            ),
        )
    assert caught.value.details["have"] == 4


def test_the_free_transceiver_count_is_reported():
    live = board()
    assert live.transceivers_free == 12
    assert board(slots=()).transceivers_free == 16


def test_a_card_wanting_another_adjustable_rail_is_refused():
    with pytest.raises(ConfigurationError) as caught:
        board(
            slots=(BoardSlot("fmc0", "fmc-hpc", "fmc-ddr4-sodimm"),),
            vadj_millivolts=1800,
        )
    assert caught.value.details["wants"] == 1200


def test_the_same_card_is_fine_on_a_carrier_strapped_for_it():
    live = board(slots=(BoardSlot("fmc0", "fmc-hpc", "fmc-ddr4-sodimm"),), vadj_millivolts=1200)
    assert live.fitted_cards()[0].name == "fmc-ddr4-sodimm"


def test_a_card_with_no_rail_requirement_goes_anywhere():
    live = board(slots=(BoardSlot("pmod0", "pmod", "pmod-ad2"),), vadj_millivolts=2500)
    assert live.card_power_mw == 180


def test_card_power_adds_up():
    live = board(
        slots=(
            BoardSlot("fmc0", "fmc-hpc", "fmc-adc-4ch-14b"),
            BoardSlot("pmod0", "pmod", "pmod-ad2"),
        )
    )
    assert live.card_power_mw == 9800 + 180


def test_a_cable_that_cannot_drive_the_header_is_refused():
    with pytest.raises(ConfigurationError):
        board(
            adapter=AdapterUnit(serial="210308AB1234", model="ft2232h-mini"),
            header_voltage_mv=1800,
        )


def test_the_same_cable_is_fine_on_a_board_at_its_level():
    live = board(
        adapter=AdapterUnit(serial="210308AB1234", model="ft2232h-mini"),
        header_voltage_mv=3300,
    )
    assert live.adapter is not None
    assert live.adapter.model == "ft2232h-mini"


def test_a_board_with_no_cable_is_allowed_because_they_get_borrowed():
    assert board(adapter=None).adapter is None


def test_a_board_with_no_clock_ceiling_is_refused():
    with pytest.raises(ValidationError):
        board(max_tck_hz=0)


def test_an_unstocked_part_or_flash_is_refused():
    with pytest.raises(NotFound):
        board(part="XC7V585T-2FFG1157C")
    with pytest.raises(NotFound):
        board(flash="at45db161")


def test_fitting_a_card_rebuilds_and_rechecks_the_board():
    live = board().with_card("fmc1", "fmc-adc-2ch-16b")
    assert live.slot("fmc1").fitted == "fmc-adc-2ch-16b"
    assert len(live.fitted_cards()) == 2


def test_fitting_a_card_that_breaks_a_rule_is_refused():
    live = board(part="XC7A100T-2FGG484I", slots=(BoardSlot("fmc0", "fmc-hpc"),))
    with pytest.raises(ConfigurationError):
        live.with_card("fmc0", "fmc-ddr4-sodimm")


def test_removing_a_card_leaves_the_slot_empty():
    live = board().with_card("fmc0", None)
    assert live.slot("fmc0").empty
    assert live.fitted_cards() == ()
    assert len(live.empty_slots()) == 2


def test_a_board_describes_itself_for_a_listing():
    described = board().describe()
    assert described.startswith("bx-14 rx-carrier-b rev a XC7K325T-2FFG900I")
    assert "fmc-adc-4ch-14b" in described
    assert "bare" in board(slots=()).describe()


def test_a_summary_is_the_short_form():
    summary = BoardSummary.of(board())
    assert summary == BoardSummary(
        tag="bx-14",
        model="rx-carrier-b",
        device="xc7k325t",
        location="rack-2/shelf-04/slot-3",
        cards=("fmc-adc-4ch-14b",),
    )
