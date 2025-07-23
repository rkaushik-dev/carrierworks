from boardsetup import artix, kintex
from fpga.inventory.chains import CARRIER_TAPS, chain_for, reconcile
from fpga.jtag.bits import BitVector
from fpga.jtag.chain import ScanChain, TapPosition
from fpga.jtag.discover import scan_bits_for

SYSCTL = 0x06E5E093


def scan_of(chain):
    return scan_bits_for(chain)


def test_a_plain_carrier_presents_one_device():
    chain = chain_for(artix("bx-15"))
    assert len(chain) == 1
    assert chain.taps[0].device == "xc7a100t"


def test_a_carrier_with_a_controller_presents_it_first():
    chain = chain_for(kintex("bx-14"))
    assert chain.labels() == ("sysctl", "u1")
    assert chain.taps[0].expected_idcode == SYSCTL
    assert chain.taps[0].ir_length == 8
    assert chain.taps[1].device == "xc7k325t"


def test_the_controller_widths_add_into_the_instruction_register():
    assert chain_for(kintex("bx-14")).ir_length == 14


def test_the_carrier_table_is_the_only_place_controllers_are_named():
    assert "rx-carrier-b" in CARRIER_TAPS
    assert "tx-carrier-a" not in CARRIER_TAPS


def test_a_healthy_scan_reconciles():
    board = kintex("bx-14")
    check = reconcile(board, scan_of(chain_for(board)))
    assert check.ok
    assert check.fault_kind is None
    assert check.next_state() is None
    assert check.describe().startswith("bx-14 chain matches")


def test_a_healthy_scan_of_a_plain_carrier_reconciles_too():
    board = artix("bx-15")
    assert reconcile(board, scan_of(chain_for(board))).ok


def test_an_open_chain_is_a_fault_worth_retrying():
    check = reconcile(kintex("bx-14"), BitVector.ones(96))
    assert not check.ok
    assert check.retry_worthwhile
    assert check.fault_kind == "chain"
    assert check.next_state() == "faulted"
    assert "open chain" in check.describe()


def test_a_board_with_no_power_reads_as_a_fault():
    check = reconcile(kintex("bx-14"), BitVector.zeros(96))
    assert check.retry_worthwhile
    assert "no power" in check.describe()


def test_the_wrong_part_fitted_sends_the_board_to_maintenance():
    board = kintex("bx-14")
    wrong = ScanChain(
        (
            TapPosition(label="sysctl", ir_length=8, expected_idcode=SYSCTL),
            TapPosition.for_device("u1", "xc7k70t"),
        )
    )
    check = reconcile(board, scan_of(wrong))
    assert not check.ok
    assert not check.retry_worthwhile
    assert check.next_state() == "maintenance"
    assert check.differences[0].kind == "device"
    assert check.differences[0].position == 1


def test_a_missing_controller_shows_up_as_a_length_difference():
    board = kintex("bx-14")
    without = ScanChain((TapPosition.for_device("u1", "xc7k325t"),))
    check = reconcile(board, scan_of(without))
    kinds = [difference.kind for difference in check.differences]
    assert "length" in kinds


def test_a_chain_plugged_in_backwards_is_reported_as_order():
    board = kintex("bx-14")
    swapped = ScanChain(
        (
            TapPosition.for_device("u1", "xc7k325t"),
            TapPosition(label="sysctl", ir_length=8, expected_idcode=SYSCTL),
        )
    )
    check = reconcile(board, scan_of(swapped))
    assert [difference.kind for difference in check.differences] == ["order", "order"]


def test_the_difference_names_what_it_expected_and_what_answered():
    board = kintex("bx-14")
    wrong = ScanChain(
        (
            TapPosition(label="sysctl", ir_length=8, expected_idcode=SYSCTL),
            TapPosition.for_device("u1", "xc7a35t"),
        )
    )
    difference = reconcile(board, scan_of(wrong)).differences[0]
    assert difference.expected == "xc7k325t"
    assert difference.found == "xc7a35t"


def test_the_check_carries_what_answered_for_the_log():
    board = kintex("bx-14")
    check = reconcile(board, scan_of(chain_for(board)))
    assert "xc7k325t" in check.found
    assert "0x06e5e093" in check.found


def test_a_scan_that_stops_part_way_is_a_wiring_story():
    check = reconcile(kintex("bx-14"), BitVector(0x03631093, 32).slice(0, 20))
    assert check.retry_worthwhile
    assert not check.ok
