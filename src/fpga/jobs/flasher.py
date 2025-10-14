"""Writing an image into the configuration flash, and reading it back.

The driver interface is four calls because that is all SPI flash does: say who
you are, erase a sector, program a page, read anything. Everything else the
bench needs is arithmetic on top of those, and the arithmetic is already in the
plan.

The fake part in here is not a stub. It models the one behaviour that makes
flash different from memory: programming can only turn ones into zeros. A write
into a sector that was not erased first produces the bitwise and of what was
there and what was sent, which is neither image and looks like a corrupt file
rather than like a missing erase. That is the bug this fake exists to catch,
and it caught it once already in the plan builder.

Verification reads the image back and compares it. It is not optional in
practice: a flash near the end of its life fails to hold what was written to
it, and the whole point of the wear ledger is knowing which parts those are.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol, runtime_checkable

from fpga.catalog.flashparts import FlashSpec
from fpga.errors import BenchError, ValidationError
from fpga.flash.plan import FlashPlan

__all__ = [
    "FakeFlash",
    "FlashOutcome",
    "SpiFlashDriver",
    "VerifyFailed",
    "write_image",
]


class VerifyFailed(BenchError):
    """The image read back is not the image that was written.

    Deliberately not retryable. A verify failure means the part did not hold
    what it was given, and doing it again writes over the evidence.
    """

    code = "verify"


@runtime_checkable
class SpiFlashDriver(Protocol):
    """Whatever can talk to the flash beside a device."""

    def read_id(self) -> int: ...

    def erase(self, address: int, length: int) -> None: ...

    def program(self, address: int, data: bytes) -> None: ...

    def read(self, address: int, length: int) -> bytes: ...


@dataclass(slots=True)
class FakeFlash:
    """A part that behaves like flash, including the awkward part."""

    spec: FlashSpec
    storage: bytearray = field(default_factory=bytearray)
    erases: int = 0
    writes: int = 0
    weak_bytes: set[int] = field(default_factory=set)

    def __post_init__(self) -> None:
        if not self.storage:
            self.storage = bytearray(b"\xff" * self.spec.capacity_bytes)

    def read_id(self) -> int:
        return self.spec.jedec_id

    def erase(self, address: int, length: int) -> None:
        self.spec.check_address(address)
        if address % self.spec.subsector_bytes:
            raise ValidationError("an erase starts on a subsector", field="address", given=address)
        self.storage[address : address + length] = b"\xff" * length
        self.erases += 1

    def program(self, address: int, data: bytes) -> None:
        self.spec.check_address(address)
        if len(data) > self.spec.page_bytes:
            raise ValidationError("a program is one page at most", field="data", given=len(data))
        if (address % self.spec.page_bytes) + len(data) > self.spec.page_bytes:
            raise ValidationError("a page write cannot cross a page", field="address")
        for offset, value in enumerate(data):
            at = address + offset
            # Programming clears bits and never sets them, which is the whole
            # reason an erase has to come first.
            written = self.storage[at] & value
            self.storage[at] = 0xFF if at in self.weak_bytes else written
        self.writes += 1

    def read(self, address: int, length: int) -> bytes:
        self.spec.check_address(address)
        return bytes(self.storage[address : address + length])


@dataclass(frozen=True, slots=True)
class FlashOutcome:
    """What one flash write did."""

    region: str
    bytes_written: int
    erases: int
    pages: int
    verified: bool

    def describe(self) -> str:
        checked = "verified" if self.verified else "not verified"
        return (
            f"{self.region}: {self.bytes_written} bytes in {self.pages} pages, "
            f"{self.erases} erases, {checked}"
        )


def write_image(
    driver: SpiFlashDriver, plan: FlashPlan, data: bytes, *, start: int
) -> FlashOutcome:
    """Run a flash plan against a driver and check what it left behind."""
    if len(data) != plan.image_bytes:
        raise ValidationError(
            "the plan was built for a different image",
            field="data",
            planned=plan.image_bytes,
            given=len(data),
        )
    if driver.read_id() != plan.part.jedec_id:
        raise ValidationError(
            "that is not the part the plan was built for",
            field="flash",
            expected=f"0x{plan.part.jedec_id:06x}",
            found=f"0x{driver.read_id():06x}",
        )
    erases = 0
    pages = 0
    for step in plan.steps:
        if step.kind == "erase":
            driver.erase(step.address, step.length)
            erases += 1
        elif step.kind == "program":
            offset = step.address - start
            driver.program(step.address, data[offset : offset + step.length])
            pages += 1
        elif step.kind == "verify":
            offset = step.address - start
            wanted = data[offset : offset + step.length]
            found = driver.read(step.address, step.length)
            if found != wanted:
                raise VerifyFailed(
                    "the flash did not hold what was written to it",
                    region=plan.region,
                    address=f"0x{step.address:08x}",
                    length=step.length,
                )
    return FlashOutcome(
        region=plan.region,
        bytes_written=plan.programmed_bytes,
        erases=erases,
        pages=pages,
        verified=plan.verifies,
    )
