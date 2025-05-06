import pytest

from fpga.bitstream.bitfile import build_bitfile
from fpga.bitstream.image import load_bitstream, read_bitstream
from fpga.bitstream.packets import SYNC_WORD, type1, type2, words_to_bytes
from fpga.errors import DeviceMismatch, ValidationError

ARTIX = 0x03631093
KINTEX = 0x03651093


def config_words(idcode=ARTIX, start=True, iprog=False, frames=8):
    words = [0xFFFFFFFF, SYNC_WORD]
    if iprog:
        words += [type1(opcode=2, register=4, word_count=1), 15]
    words += [
        type1(opcode=2, register=4, word_count=1),
        7,
        type1(opcode=2, register=12, word_count=1),
        idcode,
        type1(opcode=2, register=4, word_count=1),
        1,
        type1(opcode=2, register=2, word_count=0),
        type2(opcode=2, word_count=frames),
    ]
    words += list(range(frames))
    if start:
        words += [type1(opcode=2, register=4, word_count=1), 5]
    words += [type1(opcode=2, register=4, word_count=1), 13]
    return words_to_bytes(words)


def bitfile(part="7a100tfgg484", design="radar_frontend;Version=2024.1", **kwargs):
    return build_bitfile(
        design=design,
        part=part,
        date="2025/05/01",
        time="09:12:44",
        data=config_words(**kwargs),
    )


def test_an_image_reads_its_own_header_and_packets():
    image = read_bitstream(bitfile(), source="radar.bit")
    assert image.design == "radar_frontend"
    assert image.device == "xc7a100t"
    assert image.source == "radar.bit"
    assert image.commands == ("rcrc", "wcfg", "start", "desync")


def test_the_digest_covers_the_image_and_not_the_header():
    first = read_bitstream(bitfile())
    second = read_bitstream(
        build_bitfile(
            design="radar_frontend;Version=2024.1",
            part="7a100tfgg484",
            date="2026/01/01",
            time="23:59:59",
            data=config_words(),
        )
    )
    assert first.digest == second.digest
    assert first.short_digest == first.digest[:12]


def test_a_different_image_gets_a_different_digest():
    assert read_bitstream(bitfile()).digest != read_bitstream(bitfile(frames=9)).digest


def test_the_declared_idcode_is_read_out_of_the_packets():
    image = read_bitstream(bitfile())
    assert image.declared_idcode is not None
    assert image.declared_idcode.value == ARTIX


def test_a_full_image_starts_the_device():
    assert not read_bitstream(bitfile()).is_partial


def test_a_partial_image_does_not():
    image = read_bitstream(bitfile(start=False))
    assert image.is_partial
    assert "partial" in image.summary()


def test_a_multiboot_image_carries_the_jump_command():
    assert read_bitstream(bitfile(iprog=True)).is_multiboot
    assert not read_bitstream(bitfile()).is_multiboot


def test_an_image_checks_out_against_the_device_it_is_for():
    read_bitstream(bitfile()).check_for("xc7a100t")


def test_an_image_for_another_device_is_refused_on_its_idcode():
    image = read_bitstream(bitfile(idcode=KINTEX))
    with pytest.raises(DeviceMismatch) as caught:
        image.check_for("xc7a100t")
    assert caught.value.expected == "xc7a100t"
    assert caught.value.found == "0x03651093"


def test_the_idcode_write_outranks_the_header():
    # header says kintex, packets say artix, and the packets are believed
    image = read_bitstream(bitfile(part="7k325tffg900", idcode=ARTIX))
    image.check_for("xc7a100t")
    with pytest.raises(DeviceMismatch):
        image.check_for("xc7k325t")


def test_an_image_without_an_idcode_write_falls_back_to_its_header():
    data = build_bitfile(
        design="top",
        part="7a100tfgg484",
        date="d",
        time="t",
        data=words_to_bytes([SYNC_WORD, type1(opcode=2, register=4, word_count=1), 5]),
    )
    image = read_bitstream(data)
    assert image.declared_idcode is None
    image.check_for("xc7a100t")
    with pytest.raises(DeviceMismatch):
        image.check_for("xc7k325t")


def test_the_fill_fraction_says_how_much_of_the_device_the_image_covers():
    image = read_bitstream(bitfile())
    assert 0.0 < image.fills() < 0.01
    assert image.spec() is not None


def test_an_image_for_a_device_nobody_stocks_fills_nothing():
    image = read_bitstream(bitfile(part="7v585tffg1157"))
    assert image.spec() is None
    assert image.fills() == 0.0


def test_an_empty_image_is_refused():
    data = build_bitfile(design="top", part="7a100tfgg484", date="d", time="t", data=b"")
    with pytest.raises(ValidationError):
        read_bitstream(data)


def test_a_file_is_loaded_from_disk_with_its_name(tmp_path):
    path = tmp_path / "radar_frontend.bit"
    path.write_bytes(bitfile())
    image = load_bitstream(path)
    assert image.source == "radar_frontend.bit"
    assert image.design == "radar_frontend"


def test_a_missing_file_is_refused(tmp_path):
    with pytest.raises(ValidationError):
        load_bitstream(tmp_path / "nothing.bit")


def test_the_summary_reads_like_a_log_line():
    image = read_bitstream(bitfile())
    assert image.summary().startswith("radar_frontend full for xc7a100t")
