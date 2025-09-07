"""Switching outlets, and the fake that stands in for the real units.

The distribution units in the lab speak three different protocols and one of
them is a telnet session that drops if you look at it. None of that belongs
anywhere near the scheduler, so the interface is four calls wide and every
driver behind it is somebody else's problem.

Power cycling is the operation worth being careful about. Off then on with no
wait in between is not a power cycle: the bulk capacitance on a carrier holds
the rails up for tens of milliseconds and the device never sees a reset. The
controller enforces a minimum off time rather than trusting the caller, because
the caller is usually a retry loop that has no idea why the last attempt
failed.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol, runtime_checkable

from fpga.errors import PowerFault, ValidationError
from fpga.inventory.boards import Board
from fpga.locations import PduChannel

__all__ = [
    "MINIMUM_OFF_MS",
    "FakePdu",
    "PduDriver",
    "PowerController",
]

# Below this the bulk capacitance keeps the rails up and nothing resets.
MINIMUM_OFF_MS = 250


@runtime_checkable
class PduDriver(Protocol):
    """Whatever can switch an outlet."""

    @property
    def name(self) -> str: ...

    def switch(self, channel: PduChannel, on: bool) -> None: ...

    def state(self, channel: PduChannel) -> bool: ...

    def current_ma(self, channel: PduChannel) -> int: ...


@dataclass(slots=True)
class FakePdu:
    """A distribution unit that only exists in memory.

    Used by the tests and by the dry run mode of the command line tool, which
    is how somebody checks what a power cycle would do to a rack without doing
    it.
    """

    outlets: dict[str, bool] = field(default_factory=dict)
    currents: dict[str, int] = field(default_factory=dict)
    unreachable: set[str] = field(default_factory=set)
    commands: list[str] = field(default_factory=list)

    @property
    def name(self) -> str:
        return "fake-pdu"

    def _key(self, channel: PduChannel) -> str:
        key = str(channel)
        if channel.pdu in self.unreachable:
            raise PowerFault("the distribution unit did not answer", pdu=channel.pdu)
        return key

    def switch(self, channel: PduChannel, on: bool) -> None:
        key = self._key(channel)
        self.outlets[key] = on
        self.commands.append(f"{'on' if on else 'off'} {key}")

    def state(self, channel: PduChannel) -> bool:
        return self.outlets.get(self._key(channel), False)

    def current_ma(self, channel: PduChannel) -> int:
        key = self._key(channel)
        if not self.outlets.get(key, False):
            return 0
        return self.currents.get(key, 800)


class PowerController:
    """Board level power, on top of whatever driver the rack has."""

    def __init__(self, driver: PduDriver, *, minimum_off_ms: int = MINIMUM_OFF_MS) -> None:
        if minimum_off_ms < MINIMUM_OFF_MS:
            raise ValidationError(
                "that off time is too short to reset a board",
                field="minimum_off_ms",
                given=minimum_off_ms,
                floor=MINIMUM_OFF_MS,
            )
        self.driver = driver
        self.minimum_off_ms = minimum_off_ms

    def _channel(self, board: Board) -> PduChannel:
        if board.pdu is None:
            raise ValidationError(
                "that board is not on a switched outlet",
                field="pdu",
                board=board.tag,
            )
        return board.pdu

    def is_on(self, board: Board) -> bool:
        return self.driver.state(self._channel(board))

    def power_on(self, board: Board) -> bool:
        """Switch a board on and say whether that changed anything."""
        channel = self._channel(board)
        if self.driver.state(channel):
            return False
        self.driver.switch(channel, True)
        return True

    def power_off(self, board: Board) -> bool:
        channel = self._channel(board)
        if not self.driver.state(channel):
            return False
        self.driver.switch(channel, False)
        return True

    def cycle(self, board: Board, *, off_ms: int | None = None) -> int:
        """Off, wait, on. Returns the wait actually used.

        The wait is clamped up to the minimum rather than refused, because
        every caller of this is already handling a failure and does not need
        another one.
        """
        wait = max(off_ms or self.minimum_off_ms, self.minimum_off_ms)
        channel = self._channel(board)
        self.driver.switch(channel, False)
        self.driver.switch(channel, True)
        return wait

    def draw_ma(self, board: Board) -> int:
        return self.driver.current_ma(self._channel(board))

    def over_budget(self, board: Board, *, headroom_mw: int = 2000) -> bool:
        """Whether the board is pulling more than its cards should account for.

        A rough check on purpose. The outlet reads whole board current at the
        wall, so this catches a short or a card that has started to fail, not
        a rail that is ten percent high.
        """
        budget = board.card_power_mw + headroom_mw
        drawn = self.draw_ma(board) * 12  # the rack feeds carriers at twelve volts
        return drawn > budget
