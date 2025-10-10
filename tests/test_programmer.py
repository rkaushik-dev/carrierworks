import pytest

from fpga.bitstream.binary import reverse_bits
from fpga.errors import BenchError
from fpga.jobs.programmer import ProgrammingFailed, load_over_jtag
from fpga.jtag.chain import ScanChain
from fpga.jtag.session import ScanSession
from fpga.jtag.simulator import ChainSimulator, SimulatedDevice

IMAGE = bytes(range(256)) * 4


def session(*devices):
    chain = ScanChain.of_devices(*(devices or ("xc7a100t",)))
    sim = ChainSimulator.for_chain(chain)
    return ScanSession(sim, chain), sim


def test_a_load_leaves_the_device_configured():
    live, sim = session()
    outcome = load_over_jtag(live, IMAGE, board="bx-14")
    assert outcome.done
    assert sim.device("u1").configured
    assert outcome.bytes_sent == len(IMAGE)


def test_the_image_arrives_bit_reversed_the_way_the_port_wants_it():
    live, sim = session()
    load_over_jtag(live, IMAGE)
    assert bytes(sim.device("u1").config_data) == reverse_bits(IMAGE)


def test_the_swap_can_be_turned_off():
    live, sim = session()
    load_over_jtag(live, IMAGE, swap=False)
    assert bytes(sim.device("u1").config_data) == IMAGE


def test_a_load_counts_its_clocks():
    live, _ = session()
    outcome = load_over_jtag(live, IMAGE)
    assert outcome.clocks > len(IMAGE) * 8
    assert outcome.clocks == live.clocks - 5  # the session reset before the load


def test_the_outcome_describes_itself():
    live, _ = session()
    outcome = load_over_jtag(live, b"\x01\x02\x03\x04", board="bx-14")
    assert outcome.describe().startswith("bx-14 u1: 4 bytes,")
    assert outcome.describe().endswith("done")


def test_loading_nothing_is_a_bug():
    live, _ = session()
    with pytest.raises(BenchError):
        load_over_jtag(live, b"")


class DeafDevice(SimulatedDevice):
    """A device that takes the image and never comes up, like a bad cable."""

    def take_config(self, written):
        return None


def test_a_device_that_does_not_come_up_is_a_retryable_failure():
    chain = ScanChain.of_devices("xc7a100t")
    deaf = DeafDevice(label="u1", idcode=0x03631093, ir_length=6)
    live = ScanSession(ChainSimulator([deaf]), chain)
    with pytest.raises(ProgrammingFailed) as caught:
        load_over_jtag(live, IMAGE, board="bx-14")
    assert caught.value.retryable
    assert caught.value.details["board"] == "bx-14"
    assert caught.value.details["status"] == "0x00000000"


def test_the_second_device_on_a_chain_can_be_programmed():
    live, sim = session("xc7a100t", "xc7k325t")
    load_over_jtag(live, IMAGE, label="u2")
    assert sim.device("u2").configured
    assert not sim.device("u1").configured


def test_programming_a_device_that_is_not_on_the_chain_is_refused():
    live, _ = session()
    with pytest.raises(BenchError):
        load_over_jtag(live, IMAGE, label="u9")


def test_a_second_load_replaces_the_first():
    live, sim = session()
    load_over_jtag(live, IMAGE)
    load_over_jtag(live, b"\x01\x02\x03\x04")
    assert bytes(sim.device("u1").config_data) == reverse_bits(b"\x01\x02\x03\x04")
    assert sim.device("u1").configured


def test_a_chain_scan_after_a_load_does_not_wipe_the_device():
    live, sim = session()
    load_over_jtag(live, IMAGE)
    live.read_idcodes()
    assert sim.device("u1").configured


def test_the_status_word_comes_back_with_the_outcome():
    live, _ = session()
    outcome = load_over_jtag(live, IMAGE)
    assert outcome.status & (1 << 14)
