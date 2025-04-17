import pytest

from fpga.catalog.idcode import IdCode
from fpga.errors import ChainError, NotFound, ValidationError
from fpga.jtag.bits import BitVector
from fpga.jtag.chain import ScanChain
from fpga.jtag.scan import INSTRUCTIONS
from fpga.jtag.session import ScanSession
from fpga.jtag.simulator import ChainSimulator
from fpga.jtag.transport import RecordingTransport
from fpga.measure import Frequency

ARTIX = 0x03631093
KINTEX = 0x03651093


def session(*devices, clock=None):
    chain = ScanChain.of_devices(*devices)
    return ScanSession(ChainSimulator.for_chain(chain), chain, clock=clock)


def test_a_session_starts_from_reset():
    live = session("xc7a100t")
    assert live.state == "test_logic_reset"
    assert live.clocks == 5


def test_the_clock_is_handed_to_the_transport():
    chain = ScanChain.of_devices("xc7a100t")
    sim = ChainSimulator.for_chain(chain)
    ScanSession(sim, chain, clock=Frequency.megahertz(6))
    assert sim.clock == Frequency.megahertz(6)


def test_idcodes_come_back_in_the_listed_order():
    live = session("xc7a100t", "xc7k325t")
    assert [code.value for code in live.read_idcodes()] == [ARTIX, KINTEX]


def test_a_single_device_chain_reads_its_one_idcode():
    assert session("xc7k325t").read_idcodes() == (IdCode(KINTEX),)


def test_verification_passes_against_the_chain_it_was_built_from():
    live = session("xc7a100t", "xc7k325t")
    assert live.verify().labels() == ("u1", "u2")


def test_verification_fails_when_the_board_carries_something_else():
    chain = ScanChain.of_devices("xc7a100t", "xc7k325t")
    wrong = ChainSimulator.of_devices("xc7a100t", "xc7a35t")
    live = ScanSession(wrong, chain)
    with pytest.raises(ChainError) as caught:
        live.verify()
    assert caught.value.details["differences"] == [
        "position 2 holds xc7a35t, expected xc7k325t",
    ]


def test_an_instruction_lands_on_the_named_device_only():
    chain = ScanChain.of_devices("xc7a100t", "xc7k325t")
    sim = ChainSimulator.for_chain(chain)
    live = ScanSession(sim, chain)
    live.load_instruction("u1", "usercode")
    assert sim.device("u1").instruction == INSTRUCTIONS["usercode"]
    assert sim.device("u2").in_bypass


def test_loading_an_instruction_on_a_device_that_is_not_there_is_a_not_found():
    live = session("xc7a100t")
    with pytest.raises(NotFound):
        live.load_instruction("u9", "idcode")


def test_a_data_shift_returns_only_that_device_s_bits():
    chain = ScanChain.of_devices("xc7a100t", "xc7k325t")
    sim = ChainSimulator.for_chain(chain)
    live = ScanSession(sim, chain)
    sim.device("u1").usercode = 0xC0FFEE01
    live.load_instruction("u1", "usercode")
    read = live.shift_dr("u1", BitVector.zeros(32))
    assert read.value == 0xC0FFEE01
    assert len(read) == 32


def test_a_data_write_reaches_the_device():
    chain = ScanChain.of_devices("xc7a100t")
    sim = ChainSimulator.for_chain(chain)
    live = ScanSession(sim, chain)
    live.load_instruction("u1", "user1")
    payload = BitVector(0x0BADC0DE, 32)
    live.shift_dr("u1", payload)
    assert sim.device("u1").last_dr_write == payload


def test_shifting_nothing_is_refused():
    live = session("xc7a100t")
    with pytest.raises(ValidationError):
        live.shift_dr("u1", BitVector(0, 0))


def test_idling_clocks_the_controller_without_moving_it():
    live = session("xc7a100t")
    before = live.clocks
    live.run_test_idle(10)
    assert live.state == "run_test_idle"
    assert live.clocks >= before + 10


def test_idling_for_a_negative_number_of_clocks_is_refused():
    live = session("xc7a100t")
    with pytest.raises(ValidationError):
        live.run_test_idle(-1)


def test_moving_to_a_state_leaves_the_session_there():
    live = session("xc7a100t")
    live.move_to("shift_dr")
    assert live.state == "shift_dr"
    live.move_to("run_test_idle")
    assert live.state == "run_test_idle"


def test_every_clock_is_counted():
    chain = ScanChain.of_devices("xc7a100t")
    recorder = RecordingTransport(ChainSimulator.for_chain(chain))
    live = ScanSession(recorder, chain)
    live.read_idcodes()
    assert live.clocks == recorder.clocks


def test_a_session_closes_its_transport():
    chain = ScanChain.of_devices("xc7a100t")
    sim = ChainSimulator.for_chain(chain)
    with ScanSession(sim, chain) as live:
        live.read_idcodes()
    assert sim.closed


def test_a_bypassed_device_during_a_read_is_reported():
    chain = ScanChain.of_devices("xc7a100t", "xc7k325t")
    sim = ChainSimulator.for_chain(chain)
    live = ScanSession(sim, chain)
    live.shift_ir({"u1": "bypass", "u2": "bypass"})
    with pytest.raises(ChainError):
        live.read_idcodes(reset=False)


def test_reading_idcodes_resets_first_so_a_left_over_instruction_does_not_matter():
    chain = ScanChain.of_devices("xc7a100t", "xc7k325t")
    sim = ChainSimulator.for_chain(chain)
    live = ScanSession(sim, chain)
    live.shift_ir({"u1": "bypass", "u2": "bypass"})
    assert [code.value for code in live.read_idcodes()] == [ARTIX, KINTEX]
