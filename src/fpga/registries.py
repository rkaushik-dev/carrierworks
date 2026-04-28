"""What the deciding parts of the bench ask of an inventory, and no more.

The runner and the thermal supervisor were both written against the in memory
board book, because that is what existed when they were written and because it
keeps their tests honest. Then the database arrived, and the choice was either
to make the repositories pretend to be a board book, which is a second
inventory with its own opinions, or to write down the handful of methods those
two callers actually use.

This is that handful. It is small on purpose. Anything that wants more than
this is doing inventory work and belongs in the service layer, where it can
use the repositories directly and take the transaction with it.
"""

from __future__ import annotations

from datetime import datetime
from typing import Protocol, runtime_checkable

from fpga.inventory.boards import Board
from fpga.inventory.state import BoardState
from fpga.jobs.models import ProgramJob
from fpga.leases.models import Lease

__all__ = [
    "BoardRegistry",
    "JobLedger",
    "LeaseRegistry",
]


@runtime_checkable
class BoardRegistry(Protocol):
    """Boards, their state, and the one way state is allowed to change."""

    def has(self, tag: str) -> bool: ...

    def board(self, tag: str) -> Board: ...

    def state(self, tag: str) -> BoardState: ...

    def all(self) -> tuple[Board, ...]: ...

    def in_state(self, state: str) -> tuple[Board, ...]: ...

    def move_to(
        self,
        tag: str,
        to_state: str,
        when: datetime,
        *,
        reason: str = ...,
        actor: str = ...,
    ) -> BoardState: ...


@runtime_checkable
class LeaseRegistry(Protocol):
    """Who holds what, and how to give it back."""

    def for_board(self, tag: str, now: datetime) -> Lease | None: ...

    def release(self, lease_id: str, now: datetime, reason: str = ...) -> Lease: ...


@runtime_checkable
class JobLedger(Protocol):
    """Reading one job back and writing what became of it.

    Two methods because that is what recovery needs. Anything that wants to
    ask the queue a question uses the repository or the store directly.
    """

    def job(self, job_id: str) -> ProgramJob: ...

    def update(self, job: ProgramJob) -> ProgramJob: ...
