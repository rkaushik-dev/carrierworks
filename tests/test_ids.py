import pytest

from fpga.errors import ValidationError
from fpga.ids import AssetTag, IdFactory, adapter_serial, design_name, slug


def test_asset_tags_come_apart():
    tag = AssetTag.parse("BX-0142")
    assert tag.prefix == "bx"
    assert tag.number == 142
    assert str(tag) == "bx-142"


def test_asset_tags_print_padded():
    assert AssetTag.parse("bx-9").printed() == "BX-0009"
    assert AssetTag.parse("hil-1200").printed() == "HIL-1200"


def test_asset_tags_sort_by_number_not_by_text():
    tags = [AssetTag.parse(t) for t in ("bx-10", "bx-9", "bx-100", "ax-50")]
    assert [str(t) for t in sorted(tags)] == ["ax-50", "bx-9", "bx-10", "bx-100"]


def test_next_tag_keeps_the_prefix():
    assert AssetTag.parse("bx-9").next() == AssetTag("bx", 10)


@pytest.mark.parametrize("text", ["", "bx", "bx-", "b-1", "board-0001", "bx-0", "bx-999999", "1-1"])
def test_bad_asset_tags_are_refused(text):
    with pytest.raises(ValidationError):
        AssetTag.parse(text)


def test_a_hand_built_tag_is_checked_too():
    with pytest.raises(ValidationError):
        AssetTag(prefix="board", number=1)
    with pytest.raises(ValidationError):
        AssetTag(prefix="bx", number=0)


def test_serials_normalise_to_upper_case():
    assert adapter_serial(" 210308ab1234 ") == "210308AB1234"


@pytest.mark.parametrize("text", ["", "abc", "21030-8AB1234", "x" * 20])
def test_bad_serials_are_refused(text):
    with pytest.raises(ValidationError):
        adapter_serial(text)


@pytest.mark.parametrize(
    ("given", "expected"),
    [
        ("radar_frontend", "radar_frontend"),
        ("Radar Frontend", "radar_frontend"),
        ("radar-frontend/top", "radar_frontend_top"),
        ("  beamformer  v2 ", "beamformer_v2"),
        ("adc__capture", "adc_capture"),
    ],
)
def test_design_names_land_where_the_tools_would_put_them(given, expected):
    assert design_name(given) == expected


@pytest.mark.parametrize("text", ["", "ab", "9lives", "___", "x" * 70])
def test_bad_design_names_are_refused(text):
    with pytest.raises(ValidationError):
        design_name(text)


@pytest.mark.parametrize(
    ("given", "expected"),
    [
        ("Signal Processing", "signal-processing"),
        ("rack_4", "rack-4"),
        ("--qual--", "qual"),
    ],
)
def test_slugs_normalise(given, expected):
    assert slug(given) == expected


@pytest.mark.parametrize("text", ["", "-", "x", "!!"])
def test_bad_slugs_are_refused(text):
    with pytest.raises(ValidationError):
        slug(text)


def test_ids_are_sequential_and_prefixed():
    factory = IdFactory("job")
    assert factory.next() == "job-00001"
    assert factory.next() == "job-00002"
    assert factory.issued == 2


def test_a_factory_can_carry_on_from_the_database():
    factory = IdFactory("lease", start=4211)
    assert factory.peek() == "lease-04211"
    assert factory.next() == "lease-04211"
    assert factory.peek() == "lease-04212"


def test_peeking_does_not_spend_an_id():
    factory = IdFactory("run")
    factory.peek()
    factory.peek()
    assert factory.next() == "run-00001"


def test_the_id_space_can_run_out():
    factory = IdFactory("run", start=999, width=3)
    assert factory.next() == "run-999"
    with pytest.raises(ValidationError):
        factory.next()


@pytest.mark.parametrize(("prefix", "start", "width"), [("", 1, 5), ("job", 0, 5), ("job", 1, 2)])
def test_a_nonsense_factory_is_refused(prefix, start, width):
    with pytest.raises(ValidationError):
        IdFactory(prefix, start=start, width=width)
