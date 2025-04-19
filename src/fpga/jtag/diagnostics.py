"""Reading a chain more than once, because the interesting faults are the ones
that come and go.

A single scan answers one question: is the chain right now what the inventory
says. That is enough to decide whether to program a board and not enough to
decide anything about the board itself. A header with one cracked joint reads
perfectly two times in three, and the board goes back into service, and the
next regression fails somewhere entirely unrelated.

So the diagnosis here scans several times and reports the shape of what it saw.
Consistently right, consistently wrong and sometimes right are three different
findings with three different remedies, and only the last one needs a person
with a magnifier.
"""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass

from fpga.errors import BenchError
from fpga.jtag.bits import BitVector
from fpga.jtag.chain import ScanChain
from fpga.jtag.session import ScanSession
from fpga.jtag.verify import diagnose_capture, verify_chain

__all__ = [
    "ChainAttempt",
    "ChainDiagnosis",
    "diagnose_chain",
]


@dataclass(frozen=True, slots=True)
class ChainAttempt:
    """One scan and what came of it."""

    index: int
    capture: BitVector | None
    error: str | None

    @property
    def ok(self) -> bool:
        return self.error is None


@dataclass(frozen=True, slots=True)
class ChainDiagnosis:
    """What several scans of one chain add up to."""

    expected: str
    attempts: tuple[ChainAttempt, ...]

    @property
    def good(self) -> int:
        return sum(1 for attempt in self.attempts if attempt.ok)

    @property
    def bad(self) -> int:
        return len(self.attempts) - self.good

    @property
    def stable(self) -> bool:
        """Whether every scan read back exactly the same bits."""
        captures = {
            str(attempt.capture) for attempt in self.attempts if attempt.capture is not None
        }
        return len(captures) <= 1

    @property
    def verdict(self) -> str:
        if not self.attempts:
            return "not tested"
        if self.bad == 0:
            return "healthy"
        if self.good == 0:
            return "failed"
        return "intermittent"

    @property
    def worth_a_retry(self) -> bool:
        """An intermittent chain is worth another go; a dead one is not."""
        return self.verdict == "intermittent"

    def first_error(self) -> str | None:
        for attempt in self.attempts:
            if attempt.error is not None:
                return attempt.error
        return None

    def common_error(self) -> str | None:
        """The complaint that came up most often, which is usually the real one."""
        errors = Counter(attempt.error for attempt in self.attempts if attempt.error)
        if not errors:
            return None
        return errors.most_common(1)[0][0]

    def describe(self) -> str:
        agreed = f"{self.good} of {len(self.attempts)} scans agreed with {self.expected}"
        line = f"{self.verdict}: {agreed}"
        if self.verdict == "healthy":
            return line
        error = self.common_error()
        if error:
            line = f"{line}, {error}"
        if not self.stable and self.verdict != "healthy":
            line = f"{line}, and the scans did not even agree with each other"
        return line


def diagnose_chain(
    session: ScanSession,
    expected: ScanChain | None = None,
    *,
    attempts: int = 3,
) -> ChainDiagnosis:
    """Scan a chain several times and report the pattern rather than a verdict."""
    if attempts < 1:
        raise ValueError("a diagnosis needs at least one scan")
    wanted = expected or session.chain
    results: list[ChainAttempt] = []
    for index in range(attempts):
        capture: BitVector | None = None
        error: str | None = None
        try:
            capture = session.scan_idcodes()
            wiring = diagnose_capture(capture)
            if wiring is not None:
                error = wiring
            else:
                verify_chain(wanted, capture)
        except BenchError as failure:
            error = failure.message
        results.append(ChainAttempt(index=index, capture=capture, error=error))
    return ChainDiagnosis(expected=wanted.describe(), attempts=tuple(results))
