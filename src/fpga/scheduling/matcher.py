"""Choosing between the boards that would all do.

Any board that meets the requirements can run the job, so the choice is about
what it costs the lab rather than about the job. Four things decide it, in this
order, and the order is the policy.

A board that has been faulting recently goes last. It might be fine. It might
also fail this job and cost the person an hour, and there are usually other
boards.

Then the smallest board that fits. Putting a job that needs an Artix on the one
Kintex with the converter card is how the person who actually needs that card
finds nothing free. Surplus is counted in capabilities first and in device size
second, because a spare card is scarcer than spare logic.

Then the board that has been idle longest, which spreads wear and, more
usefully, keeps the boards nobody has touched for a month in circulation where
their faults get found.

The board name breaks the tie, so the same inputs always give the same answer
and a scheduling decision can be reproduced from a log.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from fpga.capabilities import RequirementSet
from fpga.inventory.boards import Board
from fpga.inventory.profile import board_profile

__all__ = [
    "BoardScore",
    "choose",
    "rank",
]

# Logic surplus is counted in thousands of luts, because a difference of a few
# hundred between two carriers is not a reason to prefer either.
_LUT_BUCKET = 1000


@dataclass(frozen=True, slots=True)
class BoardScore:
    """Why one board came out ahead of another."""

    board: str
    fault_penalty: int
    surplus_tokens: int
    surplus_luts: int
    idle_seconds: int

    @property
    def key(self) -> tuple[int, int, int, int, str]:
        return (
            self.fault_penalty,
            self.surplus_tokens,
            self.surplus_luts,
            -self.idle_seconds,
            self.board,
        )

    def describe(self) -> str:
        return (
            f"{self.board}: {self.surplus_tokens} spare capabilities, "
            f"{self.surplus_luts}k spare luts, idle {self.idle_seconds}s, "
            f"fault penalty {self.fault_penalty}"
        )


def _asked_for(wanted: RequirementSet) -> set[str]:
    return {req.key for req in wanted if req.kind == "present"}


def score_board(
    board: Board,
    wanted: RequirementSet,
    *,
    now: datetime,
    last_used: datetime | None = None,
    recent_faults: int = 0,
) -> BoardScore:
    """What this board would cost the lab, lower being better."""
    profile = board_profile(board)
    surplus_tokens = len(profile.tokens - _asked_for(wanted) - {"jtag"})
    surplus_luts = profile.number("luts") // _LUT_BUCKET
    idle = 0 if last_used is None else max(int((now - last_used).total_seconds()), 0)
    return BoardScore(
        board=board.tag,
        fault_penalty=max(recent_faults, 0),
        surplus_tokens=surplus_tokens,
        surplus_luts=surplus_luts,
        idle_seconds=idle,
    )


def rank(
    boards: tuple[Board, ...],
    wanted: RequirementSet,
    *,
    now: datetime,
    last_used: dict[str, datetime] | None = None,
    faults: dict[str, int] | None = None,
) -> tuple[BoardScore, ...]:
    """Every board that matches, best first.

    Boards that do not match are left out rather than scored badly. A board
    without the card the job needs is not a worse choice, it is not a choice.
    """
    used = last_used or {}
    counts = faults or {}
    scored = [
        score_board(
            board,
            wanted,
            now=now,
            last_used=used.get(board.tag),
            recent_faults=counts.get(board.tag, 0),
        )
        for board in boards
        if wanted.satisfied_by(board_profile(board))
    ]
    return tuple(sorted(scored, key=lambda score: score.key))


def choose(
    boards: tuple[Board, ...],
    wanted: RequirementSet,
    *,
    now: datetime,
    last_used: dict[str, datetime] | None = None,
    faults: dict[str, int] | None = None,
) -> Board | None:
    """The board to use, or nothing when none of them will do."""
    ordered = rank(boards, wanted, now=now, last_used=last_used, faults=faults)
    if not ordered:
        return None
    by_tag = {board.tag: board for board in boards}
    return by_tag[ordered[0].board]
