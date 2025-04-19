import pytest

from fpga.jtag.bits import BitVector
from fpga.jtag.chain import ScanChain
from fpga.jtag.diagnostics import ChainAttempt, ChainDiagnosis, diagnose_chain
from fpga.jtag.session import ScanSession
from fpga.jtag.simulator import ChainSimulator
from fpga.measure import Frequency


class FlakyTransport:
    """A simulator that goes open circuit on the scans you name."""

    def __init__(self, inner, bad_scans=()):
        self.inner = inner
        self.bad_scans = set(bad_scans)
        self.long_pulses = 0

    @property
    def name(self):
        return "flaky"

    def set_clock(self, clock: Frequency) -> None:
        self.inner.set_clock(clock)

    def close(self) -> None:
        self.inner.close()

    def pulse(self, tms, tdi):
        real = self.inner.pulse(tms, tdi)
        if len(tms) < 32:
            return real
        self.long_pulses += 1
        if self.long_pulses in self.bad_scans:
            return BitVector.ones(len(tms))
        return real


def chain():
    return ScanChain.of_devices("xc7a100t", "xc7k325t")


def session(bad_scans=()):
    live = chain()
    return ScanSession(FlakyTransport(ChainSimulator.for_chain(live), bad_scans), live)


def test_a_good_chain_reads_healthy_every_time():
    diagnosis = diagnose_chain(session())
    assert diagnosis.verdict == "healthy"
    assert diagnosis.good == 3
    assert diagnosis.bad == 0
    assert diagnosis.stable
    assert not diagnosis.worth_a_retry


def test_a_healthy_diagnosis_describes_itself_plainly():
    diagnosis = diagnose_chain(session(), attempts=2)
    assert diagnosis.describe().startswith("healthy: 2 of 2 scans agreed with")


def test_a_chain_that_never_answers_is_a_failure_not_a_flake():
    diagnosis = diagnose_chain(session(bad_scans=(1, 2, 3)))
    assert diagnosis.verdict == "failed"
    assert diagnosis.good == 0
    assert not diagnosis.worth_a_retry
    assert "open chain" in diagnosis.describe()


def test_a_chain_that_answers_sometimes_is_intermittent():
    diagnosis = diagnose_chain(session(bad_scans=(2,)))
    assert diagnosis.verdict == "intermittent"
    assert diagnosis.good == 2
    assert diagnosis.bad == 1
    assert diagnosis.worth_a_retry


def test_an_intermittent_chain_says_the_scans_disagreed():
    diagnosis = diagnose_chain(session(bad_scans=(2,)))
    assert not diagnosis.stable
    assert "did not even agree" in diagnosis.describe()


def test_the_number_of_attempts_is_respected():
    diagnosis = diagnose_chain(session(), attempts=5)
    assert len(diagnosis.attempts) == 5
    assert [attempt.index for attempt in diagnosis.attempts] == [0, 1, 2, 3, 4]


def test_asking_for_no_attempts_is_a_bug():
    with pytest.raises(ValueError):
        diagnose_chain(session(), attempts=0)


def test_a_mismatched_chain_fails_consistently():
    expected = ScanChain.of_devices("xc7a100t", "xc7a35t")
    live = chain()
    sim = ChainSimulator.for_chain(live)
    diagnosis = diagnose_chain(ScanSession(sim, live), expected)
    assert diagnosis.verdict == "failed"
    assert diagnosis.stable
    assert diagnosis.first_error() == "the chain is not the one on file"


def test_the_common_error_is_the_one_that_came_up_most():
    attempts = (
        ChainAttempt(0, None, "open chain"),
        ChainAttempt(1, None, "held low"),
        ChainAttempt(2, None, "open chain"),
    )
    diagnosis = ChainDiagnosis(expected="u1", attempts=attempts)
    assert diagnosis.common_error() == "open chain"


def test_a_clean_diagnosis_has_no_errors_to_report():
    diagnosis = diagnose_chain(session())
    assert diagnosis.first_error() is None
    assert diagnosis.common_error() is None


def test_an_empty_diagnosis_says_it_was_not_tested():
    assert ChainDiagnosis(expected="u1", attempts=()).verdict == "not tested"
