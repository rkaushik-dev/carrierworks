import pytest

from fpga.bitstream.packets import (
    BUS_WIDTH_PATTERN,
    SYNC_WORD,
    command_names,
    find_sync,
    idcode_from,
    parse_packets,
    register_name,
    type1,
    type2,
    words_to_bytes,
)
from fpga.errors import ValidationError

ARTIX = 0x03631093


def stream(*words, preamble=True):
    head = []
    if preamble:
        head = [0xFFFFFFFF, 0xFFFFFFFF, *BUS_WIDTH_PATTERN, 0xFFFFFFFF, SYNC_WORD]
    return words_to_bytes([*head, *words])


def simple_image(idcode=ARTIX):
    return stream(
        type1(opcode=2, register=4, word_count=1),
        7,  # rcrc
        type1(opcode=2, register=12, word_count=1),
        idcode,
        type1(opcode=2, register=4, word_count=1),
        1,  # wcfg
        type1(opcode=2, register=2, word_count=0),
        type2(opcode=2, word_count=4),
        0xAAAA0000,
        0xAAAA0001,
        0xAAAA0002,
        0xAAAA0003,
        type1(opcode=2, register=4, word_count=1),
        5,  # start
        type1(opcode=2, register=4, word_count=1),
        13,  # desync
    )


def test_the_sync_word_is_found_past_the_dummies():
    data = simple_image()
    assert find_sync(data) == 6 * 4


def test_an_image_without_a_sync_word_is_refused():
    with pytest.raises(ValidationError):
        find_sync(b"\x00" * 64)


def test_the_packets_come_out_in_order():
    packets = parse_packets(simple_image())
    assert [packet.register_name for packet in packets] == [
        "cmd",
        "idcode",
        "cmd",
        "fdri",
        "fdri",
        "cmd",
        "cmd",
    ]


def test_a_type_two_packet_inherits_the_register_before_it():
    packets = parse_packets(simple_image())
    frame = packets[4]
    assert frame.kind == 2
    assert frame.register_name == "fdri"
    assert frame.word_count == 4


def test_the_idcode_write_is_the_image_s_own_statement_of_its_device():
    assert idcode_from(parse_packets(simple_image())) == ARTIX
    assert idcode_from(parse_packets(simple_image(0x03651093))) == 0x03651093


def test_an_image_that_never_writes_an_idcode_says_so():
    data = stream(type1(opcode=2, register=4, word_count=1), 5)
    assert idcode_from(parse_packets(data)) is None


def test_the_commands_read_back_by_name():
    assert command_names(parse_packets(simple_image())) == ("rcrc", "wcfg", "start", "desync")


def test_an_unknown_command_keeps_its_number():
    data = stream(type1(opcode=2, register=4, word_count=1), 30)
    assert command_names(parse_packets(data)) == ("command-30",)


def test_registers_have_names_and_unknown_ones_do_not_pretend():
    assert register_name(12) == "idcode"
    assert register_name(16) == "wbstar"
    assert register_name(300) == "register-300"


def test_a_read_packet_is_not_a_write():
    data = stream(type1(opcode=1, register=7, word_count=1), 0)
    packet = parse_packets(data)[0]
    assert packet.opcode == "read"
    assert not packet.is_write


def test_a_nop_packet_carries_nothing():
    data = stream(type1(opcode=0, register=0, word_count=0))
    packet = parse_packets(data)[0]
    assert packet.opcode == "nop"
    assert packet.word_count == 0
    assert packet.describe() == "type1 nop crc x0"


def test_the_stream_stops_at_a_pad_word():
    data = stream(
        type1(opcode=2, register=4, word_count=1),
        5,
        0xFFFFFFFF,
        type1(opcode=2, register=4, word_count=1),
        13,
    )
    assert command_names(parse_packets(data)) == ("start",)


def test_a_packet_that_runs_off_the_end_is_refused():
    data = stream(type1(opcode=2, register=2, word_count=8), 1, 2)
    with pytest.raises(ValidationError):
        parse_packets(data)


def test_a_packet_type_nobody_knows_is_refused():
    data = stream(0b111 << 29)
    with pytest.raises(ValidationError):
        parse_packets(data)


def test_a_long_frame_write_keeps_its_count_and_drops_its_payload():
    words = [type1(opcode=2, register=2, word_count=0), type2(opcode=2, word_count=200)]
    words.extend(range(200))
    packets = parse_packets(words_to_bytes([SYNC_WORD, *words]), limit=100)
    frame = packets[1]
    assert frame.word_count == 0
    assert frame.describe() == "type2 write fdri x0"


@pytest.mark.parametrize(
    ("opcode", "register", "count"),
    [(4, 4, 1), (2, 0x4000, 1), (2, 4, 0x800)],
)
def test_a_type_one_header_that_does_not_fit_is_refused(opcode, register, count):
    with pytest.raises(ValidationError):
        type1(opcode=opcode, register=register, word_count=count)


def test_a_type_two_header_that_does_not_fit_is_refused():
    with pytest.raises(ValidationError):
        type2(opcode=2, word_count=0x8000000)
    with pytest.raises(ValidationError):
        type2(opcode=9, word_count=1)


def test_a_word_that_is_not_a_word_is_refused():
    with pytest.raises(ValidationError):
        words_to_bytes([0x1_0000_0000])
