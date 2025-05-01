"""The configuration packet stream inside a bitstream.

Past the header a bitstream is a sequence of thirty two bit words, and it is
readable. Some dummy words and a bus width pattern come first, then the sync
word 0xaa995566, and everything after that is packets. A type one packet has a
register address and a word count in its header. A type two packet has only a
count and inherits the register from the type one before it, which is how a
million word frame write fits in a header field eleven bits wide.

The bench reads three things out of this. The write to the idcode register is
the image's own statement of which device it is for, and it is checked against
the part on the board before anything is programmed. The command writes say
what kind of image it is, so a partial reconfiguration image is not mistaken
for a full one. And the warm boot address in a multiboot image says where the
fallback lives.

Nothing here modifies a stream. Rewriting a bitstream is a thing the tools do.
"""

from __future__ import annotations

from dataclasses import dataclass

from fpga.errors import ValidationError

__all__ = [
    "BUS_WIDTH_PATTERN",
    "COMMANDS",
    "REGISTERS",
    "SYNC_WORD",
    "ConfigPacket",
    "command_names",
    "find_sync",
    "idcode_from",
    "parse_packets",
    "register_name",
    "type1",
    "type2",
    "words_to_bytes",
]

SYNC_WORD = 0xAA995566
BUS_WIDTH_PATTERN = (0x000000BB, 0x11220044)

REGISTERS: dict[int, str] = {
    0: "crc",
    1: "far",
    2: "fdri",
    3: "fdro",
    4: "cmd",
    5: "ctl0",
    6: "mask",
    7: "stat",
    8: "lout",
    9: "cor0",
    10: "mfwr",
    11: "cbc",
    12: "idcode",
    13: "axss",
    14: "cor1",
    16: "wbstar",
    17: "timer",
    22: "bootsts",
    24: "ctl1",
    31: "bspi",
}

COMMANDS: dict[int, str] = {
    0: "null",
    1: "wcfg",
    2: "mfw",
    3: "lfrm",
    4: "rcfg",
    5: "start",
    6: "uram",
    7: "rcrc",
    8: "aghigh",
    9: "switch",
    10: "grestore",
    11: "shutdown",
    12: "gcapture",
    13: "desync",
    15: "iprog",
    16: "crcc",
    17: "ltimer",
}

_OPCODES = {0: "nop", 1: "read", 2: "write", 3: "reserved"}


def register_name(address: int) -> str:
    return REGISTERS.get(address, f"register-{address}")


def words_to_bytes(words: tuple[int, ...] | list[int]) -> bytes:
    out = bytearray()
    for word in words:
        if not 0 <= word <= 0xFFFFFFFF:
            raise ValidationError("a configuration word is thirty two bits", field="word")
        out += word.to_bytes(4, "big")
    return bytes(out)


def type1(*, opcode: int, register: int, word_count: int) -> int:
    """Assemble a type one packet header."""
    if opcode not in _OPCODES:
        raise ValidationError("no such opcode", field="opcode", given=opcode)
    if not 0 <= register <= 0x3FFF:
        raise ValidationError("register address is fourteen bits", field="register")
    if not 0 <= word_count <= 0x7FF:
        raise ValidationError("a type one count is eleven bits", field="word_count")
    return (0b001 << 29) | (opcode << 27) | (register << 13) | word_count


def type2(*, opcode: int, word_count: int) -> int:
    """Assemble a type two packet header, which carries only a count."""
    if opcode not in _OPCODES:
        raise ValidationError("no such opcode", field="opcode", given=opcode)
    if not 0 <= word_count <= 0x7FFFFFF:
        raise ValidationError("a type two count is twenty seven bits", field="word_count")
    return (0b010 << 29) | (opcode << 27) | word_count


@dataclass(frozen=True, slots=True)
class ConfigPacket:
    """One packet out of the stream."""

    kind: int
    opcode: str
    register: int | None
    words: tuple[int, ...]

    @property
    def register_name(self) -> str:
        return "none" if self.register is None else register_name(self.register)

    @property
    def word_count(self) -> int:
        return len(self.words)

    @property
    def is_write(self) -> bool:
        return self.opcode == "write"

    def describe(self) -> str:
        return f"type{self.kind} {self.opcode} {self.register_name} x{self.word_count}"


def find_sync(data: bytes) -> int:
    """The offset of the first word after the sync word.

    The dummy words and the bus width pattern before it are not packets and
    are not word aligned to anything in particular, so the sync word is hunted
    for a byte at a time rather than a word at a time.
    """
    needle = SYNC_WORD.to_bytes(4, "big")
    index = data.find(needle)
    if index < 0:
        raise ValidationError("no sync word in this image", field="bitstream")
    return index + 4


def parse_packets(data: bytes, *, limit: int = 100_000) -> tuple[ConfigPacket, ...]:
    """Read the packet stream, stopping at the end of the data or at the limit.

    Frame writes carry hundreds of thousands of words and the bench never
    looks at them, so the payload of a packet longer than the limit is kept
    as an empty tuple and only its count survives.
    """
    offset = find_sync(data)
    packets: list[ConfigPacket] = []
    last_register: int | None = None
    while offset + 4 <= len(data):
        header = int.from_bytes(data[offset : offset + 4], "big")
        offset += 4
        kind = header >> 29
        opcode = _OPCODES[(header >> 27) & 0b11]
        register: int | None
        if kind == 0b001:
            register = (header >> 13) & 0x3FFF
            count = header & 0x7FF
            last_register = register
        elif kind == 0b010:
            register = last_register
            count = header & 0x7FFFFFF
        elif header == 0xFFFFFFFF:
            break
        else:
            raise ValidationError(
                "unknown packet type in the stream",
                field="bitstream",
                header=f"0x{header:08x}",
            )
        payload_bytes = count * 4
        if offset + payload_bytes > len(data):
            raise ValidationError(
                "a packet ran off the end of the image",
                field="bitstream",
                wanted=count,
            )
        if count <= limit:
            words = tuple(
                int.from_bytes(data[offset + index * 4 : offset + index * 4 + 4], "big")
                for index in range(count)
            )
        else:
            words = ()
        offset += payload_bytes
        packets.append(
            ConfigPacket(
                kind=1 if kind == 0b001 else 2,
                opcode=opcode,
                register=register,
                words=words,
            )
        )
    return tuple(packets)


def idcode_from(packets: tuple[ConfigPacket, ...]) -> int | None:
    """The idcode the image writes, or nothing if it never writes one."""
    for packet in packets:
        if packet.is_write and packet.register_name == "idcode" and packet.words:
            return packet.words[0]
    return None


def command_names(packets: tuple[ConfigPacket, ...]) -> tuple[str, ...]:
    """Every command the image issues, in order."""
    found: list[str] = []
    for packet in packets:
        if packet.is_write and packet.register_name == "cmd":
            found.extend(COMMANDS.get(word, f"command-{word}") for word in packet.words)
    return tuple(found)
