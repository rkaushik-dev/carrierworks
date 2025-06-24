from dataclasses import dataclass

import pytest

from fpga.catalog.devices import DEVICES
from fpga.catalog.flashparts import FLASH_PARTS
from fpga.catalog.index import CatalogIndex
from fpga.errors import CatalogError, NotFound


@dataclass(frozen=True)
class Widget:
    name: str
    code: int
    colour: str


def widgets():
    return CatalogIndex(
        "widget",
        [
            Widget("left", 1, "red"),
            Widget("right", 2, "blue"),
            Widget("middle", 3, "red"),
        ],
        key=lambda widget: widget.name,
        secondary={"code": lambda widget: widget.code},
    )


def test_entries_are_found_by_name():
    assert widgets().require("left").code == 1
    assert widgets().lookup("RIGHT ").colour == "blue"


def test_a_miss_is_nothing_or_a_not_found():
    assert widgets().lookup("nowhere") is None
    with pytest.raises(NotFound) as caught:
        widgets().require("nowhere")
    assert caught.value.kind == "widget"
    assert caught.value.key == "nowhere"


def test_a_secondary_index_finds_entries_too():
    assert widgets().find("code", 2).name == "right"
    assert widgets().find("code", 9) is None


def test_a_secondary_miss_names_the_index_and_the_value_asked_for():
    with pytest.raises(NotFound) as caught:
        widgets().require_by("code", 9, shown="0x09")
    assert caught.value.kind == "widget code"
    assert caught.value.key == "0x09"


def test_an_index_nobody_built_is_a_catalog_error():
    with pytest.raises(CatalogError):
        widgets().find("colour", "red")


def test_two_entries_with_one_name_are_refused_at_build_time():
    with pytest.raises(CatalogError):
        CatalogIndex(
            "widget",
            [Widget("left", 1, "red"), Widget("left", 2, "blue")],
            key=lambda widget: widget.name,
        )


def test_two_entries_sharing_a_secondary_value_are_refused():
    with pytest.raises(CatalogError) as caught:
        CatalogIndex(
            "widget",
            [Widget("left", 1, "red"), Widget("right", 1, "blue")],
            key=lambda widget: widget.name,
            secondary={"code": lambda widget: widget.code},
        )
    assert "share a code" in caught.value.message


def test_filtering_keeps_the_catalog_order():
    assert [widget.name for widget in widgets().where(lambda w: w.colour == "red")] == [
        "left",
        "middle",
    ]


def test_the_catalog_reports_its_own_shape():
    catalog = widgets()
    assert len(catalog) == 3
    assert catalog.names() == ("left", "middle", "right")
    assert "LEFT" in catalog
    assert "nowhere" not in catalog
    assert 4 not in catalog


def test_the_device_catalog_uses_it():
    assert DEVICES.kind == "device"
    assert DEVICES.require("xc7a100t").luts == 63400
    assert DEVICES.find("idcode", 0x03631093).device == "xc7a100t"
    assert len(DEVICES) == len(DEVICES.all())


def test_the_flash_catalog_uses_it():
    assert FLASH_PARTS.require("n25q128a13").vendor == "micron"
    assert FLASH_PARTS.find("jedec id", 0x20BA18).name == "n25q128a13"
