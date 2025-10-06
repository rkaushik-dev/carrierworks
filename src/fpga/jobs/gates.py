"""Everything checked before a programming job is allowed to touch a board.

This is the module that stops the expensive mistakes, so it is written as a
list of independent checks that all run rather than as a chain that stops at
the first no. Somebody whose job is refused wants the whole list, not one item
of it at a time across three attempts.

The checks in order of how much they have cost this lab:

The image has to be for the device that is fitted. This is checked against the
idcode the image writes, not against its file name.

A flash write has to be an image the bench trusts. Anything can be loaded over
JTAG, because it disappears at the next power cycle. The update region takes
qualified images and the golden region takes only images somebody has signed
off, because a bad golden image is a board that has to come out of the rack.

The board has to be held by whoever is asking. Programming a board somebody
else is using is indistinguishable, from their side, from the board failing.

And a worn flash does not get the golden image. If the part is near the end of
its erase life, the copy that has to survive is not the one to write to it.
"""

from __future__ import annotations

from dataclasses import dataclass

from fpga.artifacts.registry import ArtifactRecord
from fpga.catalog.devices import device_for
from fpga.flash.wear import WearLedger
from fpga.inventory.boards import Board
from fpga.inventory.state import BoardState
from fpga.jobs.models import ProgramJob
from fpga.leases.models import Lease

__all__ = [
    "GateReport",
    "check_job",
]

# Which channels each mode will accept.
_ALLOWED_CHANNELS: dict[str, tuple[str, ...]] = {
    "jtag": ("dev", "qual", "golden"),
    "flash-update": ("qual", "golden"),
    "flash-golden": ("golden",),
}


@dataclass(frozen=True, slots=True)
class GateReport:
    """What stands between a job and the board it wants."""

    job: str
    blockers: tuple[str, ...] = ()

    @property
    def allowed(self) -> bool:
        return not self.blockers

    def describe(self) -> str:
        if self.allowed:
            return f"{self.job} may run"
        return f"{self.job} refused: " + "; ".join(self.blockers)


def _device_blockers(job: ProgramJob, board: Board, record: ArtifactRecord) -> list[str]:
    blockers: list[str] = []
    fitted = device_for(board.part)
    if record.device != fitted.device:
        blockers.append(f"the image is for {record.device} and the board carries {fitted.device}")
    if record.is_partial and job.writes_flash:
        blockers.append("a partial image cannot be written to flash")
    if job.writes_flash and record.length > board.flash_spec().capacity_bytes:
        blockers.append("the image is bigger than the flash on this board")
    if not job.writes_flash and record.length > fitted.config_bytes:
        blockers.append("the image is longer than this device's configuration")
    return blockers


def _lease_blockers(job: ProgramJob, state: BoardState, lease: Lease | None) -> list[str]:
    blockers: list[str] = []
    if lease is None:
        blockers.append("nobody holds a lease on that board")
    elif lease.holder != job.requested_by:
        blockers.append(f"the board is leased to {lease.holder}")
    if state.state not in ("leased", "programming"):
        blockers.append(f"the board is {state.state}")
    return blockers


def check_job(
    job: ProgramJob,
    *,
    board: Board,
    record: ArtifactRecord,
    state: BoardState,
    lease: Lease | None = None,
    timing_closed: bool | None = None,
    require_timing: bool = True,
    wear: WearLedger | None = None,
) -> GateReport:
    """Run every check and report all of them at once."""
    blockers: list[str] = []
    blockers.extend(_lease_blockers(job, state, lease))
    blockers.extend(_device_blockers(job, board, record))
    allowed = _ALLOWED_CHANNELS[job.mode]
    if record.channel not in allowed:
        blockers.append(
            f"a {job.mode} write takes {' or '.join(allowed)} images and this one is "
            f"in {record.channel}"
        )
    if require_timing and job.writes_flash:
        if timing_closed is None:
            blockers.append("no timing report has been read for this image")
        elif not timing_closed:
            blockers.append("the design did not close timing")
    if job.touches_golden and wear is not None and wear.is_worn(board.tag, board.flash_spec()):
        blockers.append("the flash on this board is near the end of its erase life")
    if job.writes_flash and board.adapter is not None and not board.adapter.spec.can_program_flash:
        blockers.append(f"the {board.adapter.model} on this board cannot write flash")
    if job.writes_flash and board.adapter is None:
        blockers.append("no cable is on that board")
    return GateReport(job=job.id, blockers=tuple(blockers))
