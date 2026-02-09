"""Where a job gets its cable from, and what stands in when there is none.

There is no real driver in this tree. Every adapter the lab owns speaks to a
vendor library that cannot be installed on the machine the bench runs on, so
the drivers live outside it and register themselves here at start up. What
ships is the simulator, and it is not a placeholder: the bench runs against it
in the dry run mode people use to check a job before letting it near a board,
and it is what the tests exercise.

Registration is by adapter model rather than by board. Two boards with the same
cable use the same driver, and a board with no cable has no driver at all,
which is the honest answer rather than a fake one.
"""

from __future__ import annotations

from collections.abc import Callable

from fpga.catalog.flashparts import FlashSpec
from fpga.errors import NotFound
from fpga.ids import slug
from fpga.inventory.boards import Board
from fpga.inventory.chains import chain_for
from fpga.jobs.flasher import FakeFlash, SpiFlashDriver
from fpga.jtag.session import ScanSession
from fpga.jtag.simulator import ChainSimulator
from fpga.measure import Frequency

__all__ = [
    "SIMULATED",
    "flash_for",
    "register_flash_driver",
    "register_transport",
    "session_for",
]

SIMULATED = "simulated"

_TRANSPORTS: dict[str, Callable[[Board], ScanSession]] = {}
_FLASHES: dict[str, Callable[[Board], SpiFlashDriver]] = {}


def register_transport(model: str, factory: Callable[[Board], ScanSession]) -> None:
    """Point an adapter model at whatever drives it."""
    _TRANSPORTS[slug(model)] = factory


def register_flash_driver(model: str, factory: Callable[[Board], SpiFlashDriver]) -> None:
    _FLASHES[slug(model)] = factory


def _simulated_session(board: Board) -> ScanSession:
    """A session against a simulated chain that matches the board on file.

    The simulator answers with the idcodes the inventory says the board has,
    so a job run against it exercises everything above the transport and
    nothing below it. A board whose row is wrong will still verify here, which
    is exactly the limit of what a simulation can tell anybody.
    """
    chain = chain_for(board)
    clock = Frequency(board.max_tck_hz)
    return ScanSession(ChainSimulator.for_chain(chain), chain, clock=clock)


def _simulated_flash(board: Board) -> SpiFlashDriver:
    spec: FlashSpec = board.flash_spec()
    return FakeFlash(spec=spec)


def session_for(board: Board, *, simulated: bool = False) -> ScanSession:
    """The session to talk to a board through."""
    if simulated:
        return _simulated_session(board)
    if board.adapter is None:
        raise NotFound("cable", board.tag)
    factory = _TRANSPORTS.get(board.adapter.model)
    if factory is None:
        raise NotFound("transport driver", board.adapter.model)
    return factory(board)


def flash_for(board: Board, *, simulated: bool = False) -> SpiFlashDriver:
    """The flash driver for a board, or a simulated part."""
    if simulated:
        return _simulated_flash(board)
    if board.adapter is None:
        raise NotFound("cable", board.tag)
    if not board.adapter.spec.can_program_flash:
        raise NotFound("flash driver", board.adapter.model)
    factory = _FLASHES.get(board.adapter.model)
    if factory is None:
        raise NotFound("flash driver", board.adapter.model)
    return factory(board)


def registered() -> tuple[str, ...]:
    """Which adapter models have a driver, which is what a startup log wants."""
    return tuple(sorted(set(_TRANSPORTS) | set(_FLASHES)))


def forget_all() -> None:
    """Drop every registration, which is what a test does between cases."""
    _TRANSPORTS.clear()
    _FLASHES.clear()
