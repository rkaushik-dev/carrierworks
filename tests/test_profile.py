from fpga.capabilities import RequirementSet
from fpga.inventory.boards import Board, BoardSlot
from fpga.inventory.profile import board_profile, matches, why_not
from fpga.jtag.adapters import AdapterUnit
from fpga.locations import PduChannel


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
        "pdu": PduChannel("pdu-a", 7),
    }
    fields.update(changes)
    return Board.build(**fields)


def wanted(*requirements):
    return RequirementSet.parse(list(requirements))


def test_card_capabilities_become_tokens():
    profile = board_profile(board())
    assert "adc-14bit" in profile.tokens
    assert "trigger-in" in profile.tokens
    assert "jtag" in profile.tokens


def test_a_cabled_board_says_so_and_says_whether_it_can_do_flash():
    profile = board_profile(board())
    assert "cabled" in profile.tokens
    assert "flash-programmable" in profile.tokens


def test_a_cable_that_cannot_do_flash_leaves_the_token_off():
    profile = board_profile(
        board(
            adapter=AdapterUnit(serial="210308AB1234", model="arm-usb-tiny-h"),
        )
    )
    assert "cabled" in profile.tokens
    assert "flash-programmable" not in profile.tokens


def test_a_board_with_no_cable_is_not_cabled():
    profile = board_profile(board(adapter=None))
    assert "cabled" not in profile.tokens


def test_only_a_switched_outlet_makes_a_board_power_controlled():
    assert "power-controlled" in board_profile(board()).tokens
    assert "power-controlled" not in board_profile(board(pdu=None)).tokens


def test_an_empty_slot_shows_up_as_a_spare():
    assert "spare-slot" in board_profile(board()).tokens
    full = board(slots=(BoardSlot("fmc0", "fmc-hpc", "fmc-adc-4ch-14b"),))
    assert "spare-slot" not in board_profile(full).tokens


def test_the_transceiver_count_is_what_is_left_not_what_is_fitted():
    profile = board_profile(board())
    assert profile.number("transceivers") == 12
    assert profile.number("transceivers-fitted") == 16


def test_device_resources_come_through_as_counts():
    profile = board_profile(board())
    assert profile.number("luts") == 203800
    assert profile.number("block-ram") == 445
    assert profile.number("dsp") == 840
    assert profile.number("flash-mib") == 16
    assert profile.number("tck-mhz") == 30


def test_the_board_identity_comes_through_as_attributes():
    profile = board_profile(board())
    assert profile.attribute("family") == "kintex7"
    assert profile.attribute("device") == "xc7k325t"
    assert profile.attribute("part") == "xc7k325t-2ffg900i"
    assert profile.attribute("model") == "rx-carrier-b"
    assert profile.attribute("rack") == "rack-2"
    assert profile.attribute("asset") == "bx-14"
    assert profile.attribute("vadj") == "1800"


def test_a_job_that_asks_for_what_the_board_has_matches():
    assert matches(board(), wanted("adc-14bit", "transceivers>=4", "family=kintex7"))


def test_a_job_that_asks_for_something_else_does_not():
    assert not matches(board(), wanted("ddr4-sodimm"))
    assert why_not(board(), wanted("ddr4-sodimm")) == "no ddr4-sodimm"


def test_a_job_can_ask_for_a_board_without_a_card_in_the_way():
    assert matches(board(slots=()), wanted("!adc-14bit"))
    assert not matches(board(), wanted("!adc-14bit"))


def test_a_job_can_ask_for_a_rack():
    assert matches(board(), wanted("rack=rack-2"))
    assert not matches(board(), wanted("rack=rack-4"))


def test_a_job_asking_for_more_lanes_than_are_free_is_refused_with_a_reason():
    reason = why_not(board(), wanted("transceivers>=14"))
    assert reason == "transceivers is 12, wanted >=14"


def test_every_unmet_requirement_is_reported_at_once():
    reason = why_not(board(pdu=None), wanted("power-controlled", "ddr4-sodimm", "jtag"))
    assert reason == "no power-controlled; no ddr4-sodimm"


def test_a_bare_board_still_carries_the_device_facts():
    profile = board_profile(board(slots=(), adapter=None, pdu=None))
    assert profile.tokens == frozenset({"jtag"})
    assert profile.number("transceivers") == 16
    assert profile.number("cards") == 0
