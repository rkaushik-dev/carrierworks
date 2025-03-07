"""The sixteen state test access port controller, as a table and a search.

Every JTAG operation is a walk through this machine clocked by one wire, so the
whole of it is a graph problem: get from where the port is to where the
operation needs it, in as few clocks as possible. Writing the paths out by hand
is how a driver ends up with a sequence that works from idle and hangs from
pause, so they are searched instead.

Five ones is the one path that does not need searching. From any state at all,
five clocks with TMS high lands in test logic reset, which is why every session
starts with them and why a chain that will not talk gets them again.
"""

from __future__ import annotations

from collections import deque

from fpga.errors import ValidationError

__all__ = [
    "RESET_CLOCKS",
    "STATES",
    "is_shift_state",
    "is_stable",
    "path_between",
    "reset_path",
    "step",
    "walk",
]

RESET_CLOCKS = 5

# state -> (where tms low goes, where tms high goes)
_TRANSITIONS: dict[str, tuple[str, str]] = {
    "test_logic_reset": ("run_test_idle", "test_logic_reset"),
    "run_test_idle": ("run_test_idle", "select_dr"),
    "select_dr": ("capture_dr", "select_ir"),
    "capture_dr": ("shift_dr", "exit1_dr"),
    "shift_dr": ("shift_dr", "exit1_dr"),
    "exit1_dr": ("pause_dr", "update_dr"),
    "pause_dr": ("pause_dr", "exit2_dr"),
    "exit2_dr": ("shift_dr", "update_dr"),
    "update_dr": ("run_test_idle", "select_dr"),
    "select_ir": ("capture_ir", "test_logic_reset"),
    "capture_ir": ("shift_ir", "exit1_ir"),
    "shift_ir": ("shift_ir", "exit1_ir"),
    "exit1_ir": ("pause_ir", "update_ir"),
    "pause_ir": ("pause_ir", "exit2_ir"),
    "exit2_ir": ("shift_ir", "update_ir"),
    "update_ir": ("run_test_idle", "select_dr"),
}

STATES: tuple[str, ...] = tuple(_TRANSITIONS)

# The states the controller can sit in forever with TMS held one way.
_STABLE = frozenset({"test_logic_reset", "run_test_idle", "pause_dr", "pause_ir"})


def _check(state: str) -> str:
    if state not in _TRANSITIONS:
        raise ValidationError("no such tap state", field="state", given=state)
    return state


def is_stable(state: str) -> bool:
    """Whether the controller stays put when TMS is held."""
    return _check(state) in _STABLE


def is_shift_state(state: str) -> bool:
    return _check(state) in ("shift_dr", "shift_ir")


def step(state: str, tms: bool | int) -> str:
    """One clock of TCK with TMS at the given level."""
    low, high = _TRANSITIONS[_check(state)]
    return high if tms else low


def walk(state: str, tms_bits: str | list[int] | tuple[int, ...]) -> str:
    """Where a run of TMS bits leaves the controller.

    Bits are taken in the order they go out on the wire, which is the order
    they are written, not the order an integer would number them.
    """
    current = _check(state)
    for bit in _as_bits(tms_bits):
        current = step(current, bit)
    return current


def _as_bits(bits: str | list[int] | tuple[int, ...]) -> list[int]:
    if isinstance(bits, str):
        cleaned = bits.strip().replace(" ", "").replace("_", "")
        if any(character not in "01" for character in cleaned):
            raise ValidationError("tms bits are ones and zeros", field="tms", given=bits)
        return [int(character) for character in cleaned]
    values = list(bits)
    if any(value not in (0, 1) for value in values):
        raise ValidationError("tms bits are ones and zeros", field="tms")
    return values


def reset_path() -> tuple[int, ...]:
    """The five clocks that land in reset from anywhere at all."""
    return (1,) * RESET_CLOCKS


def path_between(start: str, end: str) -> tuple[int, ...]:
    """The shortest run of TMS bits taking the controller from one state to another.

    Breadth first, and ties broken towards TMS low, because a low first path
    keeps the controller out of the select states where a stray clock lands it
    somewhere expensive.
    """
    _check(start)
    _check(end)
    if start == end:
        return ()
    seen = {start}
    queue: deque[tuple[str, tuple[int, ...]]] = deque([(start, ())])
    while queue:
        state, path = queue.popleft()
        for bit in (0, 1):
            nxt = step(state, bit)
            if nxt == end:
                return (*path, bit)
            if nxt not in seen:
                seen.add(nxt)
                queue.append((nxt, (*path, bit)))
    raise ValidationError(  # pragma: no cover - the graph is strongly connected
        "no path between those states",
        field="path",
        start=start,
        end=end,
    )
