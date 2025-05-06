"""A bitstream as the bench thinks of it: header, packets and a fingerprint.

The fingerprint is a digest of the configuration data only, not of the file.
Two builds of the same source produce files that differ in the date and time
fields of the header and are identical everywhere that matters, and the bench
wants those to look like the same image, because otherwise the registry fills
up with duplicates and nobody can tell which one is on the board.

The kind of image matters as much as its contents. A full image configures the
whole device and ends by starting it. A partial one rewrites some frames while
the device carries on running, and loading it into a device that is not already
configured does nothing useful. They are told apart here by whether the image
issues the start command, which is the only difference visible from outside the
tools that made them.
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass
from pathlib import Path

from fpga.bitstream.bitfile import BitFile, parse_bitfile
from fpga.bitstream.packets import ConfigPacket, command_names, idcode_from, parse_packets
from fpga.catalog.devices import DeviceSpec, device_for, lookup_device
from fpga.catalog.idcode import IdCode
from fpga.errors import DeviceMismatch, ValidationError

__all__ = [
    "Bitstream",
    "load_bitstream",
    "read_bitstream",
]


@dataclass(frozen=True, slots=True)
class Bitstream:
    """One image, read and understood."""

    header: BitFile
    packets: tuple[ConfigPacket, ...]
    digest: str
    source: str = ""

    @property
    def design(self) -> str:
        return self.header.design_name

    @property
    def device(self) -> str:
        return self.header.device

    @property
    def length(self) -> int:
        return self.header.length

    @property
    def commands(self) -> tuple[str, ...]:
        return command_names(self.packets)

    @property
    def declared_idcode(self) -> IdCode | None:
        """The idcode the image itself writes, which outranks the header."""
        value = idcode_from(self.packets)
        return None if value is None else IdCode(value)

    @property
    def is_partial(self) -> bool:
        """A partial image never starts the device; it assumes it is running."""
        return "start" not in self.commands

    @property
    def is_multiboot(self) -> bool:
        """An image that can jump to another one carries the iprog command."""
        return "iprog" in self.commands

    @property
    def short_digest(self) -> str:
        return self.digest[:12]

    def spec(self) -> DeviceSpec | None:
        return lookup_device(self.device)

    def fills(self) -> float:
        """How much of the device's configuration space this image covers.

        A full image is essentially all of it. Anything much under that and
        the image is partial whatever its commands say, which is worth being
        able to see.
        """
        spec = self.spec()
        if spec is None or not spec.config_bytes:
            return 0.0
        return self.length / spec.config_bytes

    def check_for(self, device: str) -> None:
        """Raise unless this image belongs on that device.

        The image's own idcode write is believed over its header, because the
        header is a string a tool wrote and the idcode is what the device will
        compare against at load time. If they disagree that is worth knowing
        too, and it is checked first.
        """
        target = device_for(device)
        declared = self.declared_idcode
        if declared is not None and not target.matches_idcode(declared.value):
            raise DeviceMismatch(
                "the image writes an idcode for another device",
                expected=target.device,
                found=str(declared),
                design=self.design,
            )
        if declared is None and self.device != target.device:
            raise DeviceMismatch(
                "the image header names another device",
                expected=target.device,
                found=self.device,
                design=self.design,
            )

    def summary(self) -> str:
        kind = "partial" if self.is_partial else "full"
        return f"{self.design} {kind} for {self.device} {self.length} bytes {self.short_digest}"


def read_bitstream(data: bytes, *, source: str = "") -> Bitstream:
    """Parse a .bit file in memory."""
    header = parse_bitfile(data)
    if not header.data:
        raise ValidationError("that bit file carries no image", field="bitstream", source=source)
    packets = parse_packets(header.data)
    digest = hashlib.sha256(header.data).hexdigest()
    return Bitstream(header=header, packets=packets, digest=digest, source=source)


def load_bitstream(path: str | Path) -> Bitstream:
    """Read a .bit file off disk."""
    location = Path(path)
    if not location.is_file():
        raise ValidationError("no bit file there", field="path", given=str(location))
    return read_bitstream(location.read_bytes(), source=location.name)
