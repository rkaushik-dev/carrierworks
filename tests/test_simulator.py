import pytest

from fpga.errors import ValidationError
from fpga.jtag.bits import BitVector
from fpga.jtag.chain import ScanChain, TapPosition
from fpga.jtag.discover import discover_taps
from fpga.jtag.scan import INSTRUCTIONS, ir_scan
from fpga.jtag.simulator import ChainSimulator, SimulatedDevice
from fpga.jtag.tap import path_between
from fpga.measure import Frequency

ARTIX = 0x03631093
KINTEX = 0x03651093


def vec(bits):
    """A bit vector from clock order, first clock first."""
    value = 0
    for index, bit in enumerate(bits):
        value |= bit << index
    return BitVector(value, len(bits))


def travel(sim, target):
    path = path_between(sim.state, target)
    if path:
        sim.pulse(vec(path), BitVector.zeros(len(path)))


def shift(sim, target, payload):
    """Shift a payload through a shift state and come back to idle."""
    travel(sim, target)
    length = len(payload)
    tms = vec([0] * (length - 1) + [1])
    out = sim.pulse(tms, payload)
    travel(sim, "run_test_idle")
    return out


def test_a_fresh_chain_is_in_reset_with_idcode_loaded():
    sim = ChainSimulator.of_devices("xc7a100t")
    assert sim.state == "test_logic_reset"
    assert sim.devices[0].instruction == INSTRUCTIONS["idcode"]


def test_a_single_device_shifts_out_its_idcode():
    sim = ChainSimulator.of_devices("xc7a100t")
    out = shift(sim, "shift_dr", BitVector.ones(32))
    assert out.value == ARTIX


def test_the_device_nearest_tdo_comes_out_first():
    sim = ChainSimulator.of_devices("xc7a100t", "xc7k325t")
    out = shift(sim, "shift_dr", BitVector.ones(64))
    assert out.slice(0, 32).value == KINTEX
    assert out.slice(32, 32).value == ARTIX


def test_the_discovery_reader_agrees_with_the_simulator():
    sim = ChainSimulator.of_devices("xc7a100t", "xc7k325t")
    out = shift(sim, "shift_dr", BitVector.ones(96))
    found = discover_taps(out)
    assert [tap.device for tap in found] == ["xc7k325t", "xc7a100t"]


def test_the_instruction_register_captures_the_standard_pattern():
    sim = ChainSimulator.of_devices("xc7a100t")
    out = shift(sim, "shift_ir", BitVector.ones(6))
    assert out == BitVector(0b01, 6)


def test_shifting_bypass_into_the_instruction_register_takes_effect():
    sim = ChainSimulator.of_devices("xc7a100t")
    shift(sim, "shift_ir", BitVector.ones(6))
    assert sim.devices[0].in_bypass


def test_a_bypassed_device_contributes_a_single_bit():
    sim = ChainSimulator.of_devices("xc7a100t", "xc7k325t")
    shift(sim, "shift_ir", ir_scan(ScanChain.of_devices("xc7a100t", "xc7k325t")))
    assert all(device.in_bypass for device in sim.devices)
    out = shift(sim, "shift_dr", BitVector.ones(2))
    assert len(out) == 2
    assert out.value == 0


def test_one_device_can_stay_in_idcode_while_the_other_bypasses():
    chain = ScanChain.of_devices("xc7a100t", "xc7k325t")
    sim = ChainSimulator.for_chain(chain)
    shift(sim, "shift_ir", ir_scan(chain, {"u1": "idcode"}))
    assert not sim.devices[0].in_bypass
    assert sim.devices[1].in_bypass
    out = shift(sim, "shift_dr", BitVector.ones(33))
    # the kintex is nearest tdo and is in bypass, so one zero comes out first.
    assert out.bit(0) == 0
    assert out.slice(1, 32).value == ARTIX


def test_a_usercode_instruction_reads_the_usercode():
    sim = ChainSimulator.of_devices("xc7a100t")
    sim.devices[0].usercode = 0xDEADBEEF
    shift(sim, "shift_ir", BitVector(INSTRUCTIONS["usercode"], 6))
    out = shift(sim, "shift_dr", BitVector.zeros(32))
    assert out.value == 0xDEADBEEF


def test_a_data_write_is_kept_for_inspection():
    sim = ChainSimulator.of_devices("xc7a100t")
    shift(sim, "shift_ir", BitVector(INSTRUCTIONS["user1"], 6))
    payload = BitVector(0x1234ABCD, 32)
    shift(sim, "shift_dr", payload)
    assert sim.devices[0].last_dr_write == payload


def test_five_ones_put_everything_back_to_idcode():
    sim = ChainSimulator.of_devices("xc7a100t")
    shift(sim, "shift_ir", BitVector.ones(6))
    assert sim.devices[0].in_bypass
    sim.pulse(BitVector.ones(5), BitVector.zeros(5))
    assert sim.state == "test_logic_reset"
    assert sim.devices[0].instruction == INSTRUCTIONS["idcode"]


def test_the_simulator_remembers_the_clock_it_was_given():
    sim = ChainSimulator.of_devices("xc7a100t")
    sim.set_clock(Frequency.megahertz(15))
    assert sim.clock == Frequency.megahertz(15)


def test_a_closed_simulator_refuses_to_clock():
    sim = ChainSimulator.of_devices("xc7a100t")
    sim.close()
    with pytest.raises(ValidationError):
        sim.pulse(BitVector.ones(1), BitVector.zeros(1))


def test_mismatched_or_empty_pulses_are_refused():
    sim = ChainSimulator.of_devices("xc7a100t")
    with pytest.raises(ValidationError):
        sim.pulse(BitVector.ones(2), BitVector.zeros(3))
    with pytest.raises(ValidationError):
        sim.pulse(BitVector(0, 0), BitVector(0, 0))


def test_a_chain_of_nothing_is_refused():
    with pytest.raises(ValidationError):
        ChainSimulator([])


def test_a_chain_with_an_unknown_device_cannot_be_simulated():
    chain = ScanChain((TapPosition(label="u1", ir_length=6),))
    with pytest.raises(ValidationError):
        ChainSimulator.for_chain(chain)


def test_devices_are_found_by_label():
    sim = ChainSimulator.of_devices("xc7a100t", "xc7k325t")
    assert sim.device("u2").idcode == KINTEX
    with pytest.raises(ValidationError):
        sim.device("u9")


def test_a_hand_built_device_can_stand_in_for_a_catalogued_one():
    sim = ChainSimulator([SimulatedDevice(label="dap", idcode=0x4BA00477, ir_length=4)])
    out = shift(sim, "shift_dr", BitVector.ones(32))
    assert out.value == 0x4BA00477
