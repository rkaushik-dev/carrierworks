import pytest

from fpga.errors import ValidationError
from fpga.jtag.bits import BitVector
from fpga.jtag.transport import (
    LoopbackTransport,
    RecordingTransport,
    ScanOperation,
    Transport,
)
from fpga.measure import Frequency


def test_the_loopback_returns_what_went_in():
    transport = LoopbackTransport()
    tdi = BitVector.parse("1011")
    assert transport.pulse(BitVector.zeros(4), tdi) == tdi


def test_a_transport_takes_a_clock():
    transport = LoopbackTransport()
    transport.set_clock(Frequency.megahertz(6))
    assert transport.clock == Frequency.megahertz(6)


def test_mismatched_lengths_are_refused():
    with pytest.raises(ValidationError):
        LoopbackTransport().pulse(BitVector.zeros(4), BitVector.zeros(5))


def test_a_pulse_of_nothing_is_refused():
    with pytest.raises(ValidationError):
        LoopbackTransport().pulse(BitVector(0, 0), BitVector(0, 0))


def test_a_closed_transport_refuses_to_clock():
    transport = LoopbackTransport()
    transport.close()
    assert transport.closed
    with pytest.raises(ValidationError):
        transport.pulse(BitVector.zeros(2), BitVector.zeros(2))


def test_both_transports_satisfy_the_protocol():
    assert isinstance(LoopbackTransport(), Transport)
    assert isinstance(RecordingTransport(LoopbackTransport()), Transport)


def test_the_recorder_passes_everything_through():
    recorder = RecordingTransport(LoopbackTransport())
    tdi = BitVector.parse("1100")
    assert recorder.pulse(BitVector.zeros(4), tdi) == tdi


def test_the_recorder_keeps_what_went_out():
    recorder = RecordingTransport(LoopbackTransport())
    recorder.pulse(BitVector.parse("11"), BitVector.parse("01"))
    recorder.pulse(BitVector.parse("0000"), BitVector.parse("1010"))
    assert len(recorder.operations) == 2
    assert recorder.clocks == 6
    assert recorder.operations[0].tdo == BitVector.parse("01")


def test_the_transcript_reads_like_a_log_line():
    recorder = RecordingTransport(LoopbackTransport())
    recorder.pulse(BitVector.parse("11"), BitVector.parse("01"))
    assert recorder.transcript() == ("2 clocks tms=11 tdi=01 tdo=01",)


def test_the_recorder_names_what_it_wraps():
    assert RecordingTransport(LoopbackTransport()).name == "recording(loopback)"


def test_the_recorder_stops_growing_at_its_limit():
    recorder = RecordingTransport(LoopbackTransport(), limit=3)
    for _ in range(10):
        recorder.pulse(BitVector.parse("1"), BitVector.parse("0"))
    assert len(recorder.operations) == 3
    assert recorder.truncated
    assert recorder.transcript()[-1] == "transcript truncated"


def test_a_short_transcript_is_not_marked_truncated():
    recorder = RecordingTransport(LoopbackTransport(), limit=3)
    recorder.pulse(BitVector.parse("1"), BitVector.parse("0"))
    assert not recorder.truncated
    assert "truncated" not in recorder.transcript()[-1]


def test_clearing_the_recorder_leaves_the_transport_alone():
    inner = LoopbackTransport()
    recorder = RecordingTransport(inner)
    recorder.pulse(BitVector.parse("1"), BitVector.parse("1"))
    recorder.clear()
    assert recorder.operations == []
    assert recorder.clocks == 0
    assert not inner.closed


def test_closing_the_recorder_closes_what_it_wraps():
    inner = LoopbackTransport()
    RecordingTransport(inner).close()
    assert inner.closed


def test_the_clock_goes_through_to_the_real_transport():
    inner = LoopbackTransport()
    RecordingTransport(inner).set_clock(Frequency.megahertz(15))
    assert inner.clock == Frequency.megahertz(15)


def test_an_operation_counts_its_own_clocks():
    operation = ScanOperation(
        tms=BitVector.parse("110"),
        tdi=BitVector.parse("000"),
        tdo=BitVector.parse("001"),
    )
    assert operation.clocks == 3
