"""Turning a board into the thing a job's requirements are matched against.

The scheduler does not know what a carrier is. It knows tokens, counts and
attributes, and this is where a board becomes those. Everything a card provides
becomes a token, everything the device has becomes a count, and the facts that
identify the board become attributes.

Two of these are worth spelling out because they are not simply copied off the
board.

The free transceiver count is what a job can actually use, not what the device
has, because a fitted card has already taken some. A job asking for four lanes
on a Kintex with sixteen will still be refused if the cards on that board have
taken thirteen of them.

The power controlled token only appears when the board has an outlet the bench
can switch. Half the boards in the lab are plugged into a wall strip, and a job
that needs to power cycle its board cannot run on one of those however well it
matches on everything else.
"""

from __future__ import annotations

from fpga.capabilities import CapabilityProfile, RequirementSet
from fpga.inventory.boards import Board

__all__ = [
    "board_profile",
    "matches",
    "why_not",
]


def board_profile(board: Board) -> CapabilityProfile:
    """Everything about a board that a requirement can ask about."""
    device = board.device()
    flash = board.flash_spec()
    tokens = {"jtag"}
    for spec in board.fitted_cards():
        tokens |= set(spec.provides)
    if board.adapter is not None:
        tokens.add("cabled")
        if board.adapter.spec.can_program_flash:
            tokens.add("flash-programmable")
    if board.pdu is not None:
        tokens.add("power-controlled")
    if board.empty_slots():
        tokens.add("spare-slot")
    numbers = {
        "luts": device.luts,
        "flip-flops": device.flip_flops,
        "block-ram": device.block_ram_36k,
        "dsp": device.dsp_slices,
        "transceivers": board.transceivers_free,
        "transceivers-fitted": device.transceivers,
        "flash-mib": flash.capacity_bytes // (1024 * 1024),
        "tck-mhz": board.max_tck_hz // 1_000_000,
        "cards": len(board.fitted_cards()),
    }
    attributes = {
        "family": device.family,
        "device": device.device,
        "part": board.part.ordering_code(),
        "package": board.part.package,
        "speed": board.part.speed_grade,
        "model": board.model,
        "revision": board.revision,
        "rack": board.location.rack,
        "vadj": str(board.vadj_millivolts),
        "flash": flash.name,
        "asset": board.tag,
    }
    return CapabilityProfile.build(tokens=tokens, numbers=numbers, attributes=attributes)


def matches(board: Board, wanted: RequirementSet) -> bool:
    """Whether a board can do what a job asked for."""
    return wanted.satisfied_by(board_profile(board))


def why_not(board: Board, wanted: RequirementSet) -> str:
    """The line an operator reads when their job will not schedule."""
    return wanted.why_not(board_profile(board))
