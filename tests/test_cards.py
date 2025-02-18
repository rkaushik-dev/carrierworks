import pytest

from fpga.catalog.cards import (
    CardSpec,
    all_cards,
    card,
    cards_providing,
    connector_accepts,
    lookup_card,
)
from fpga.errors import CatalogError, NotFound


def test_a_card_is_found_by_name():
    spec = card("FMC-ADC-2CH-16B")
    assert spec.vendor == "curtiss"
    assert "adc-16bit" in spec.provides
    assert spec.vadj_millivolts == 1800


def test_an_unstocked_card_is_a_not_found():
    assert lookup_card("fmc-quad-dac") is None
    with pytest.raises(NotFound):
        card("fmc-quad-dac")


def test_a_low_pin_count_card_drops_into_a_high_pin_count_carrier():
    assert connector_accepts("fmc-hpc", "fmc-lpc")
    assert card("fmc-adc-2ch-16b").fits("fmc-hpc")


def test_a_high_pin_count_card_does_not_go_the_other_way():
    assert not connector_accepts("fmc-lpc", "fmc-hpc")
    assert not card("fmc-sfp-quad").fits("fmc-lpc")


def test_unrelated_connectors_never_accept_each_other():
    assert not connector_accepts("pmod", "fmc-lpc")
    assert not connector_accepts("fmc-hpc", "pmod")
    assert connector_accepts("pmod", "pmod")


def test_an_unknown_carrier_connector_is_a_not_found():
    with pytest.raises(NotFound):
        connector_accepts("vita57-4", "fmc-lpc")


def test_provides_all_is_a_subset_question():
    spec = card("fmc-adc-4ch-14b")
    assert spec.provides_all({"adc-14bit", "trigger-in"})
    assert not spec.provides_all({"adc-14bit", "dac-16bit"})
    assert spec.provides_all(set())


def test_cards_providing_sorts_by_power():
    hits = [spec.name for spec in cards_providing("adc-12bit")]
    assert hits == ["pmod-ad2"]
    capture = [spec.name for spec in cards_providing("external-clock")]
    assert capture == ["fmc-adc-2ch-16b", "fmc-adc-4ch-14b"]


def test_asking_for_a_capability_nobody_provides_gives_nothing():
    assert cards_providing("quantum-link") == ()


def test_only_cards_with_a_vadj_ask_for_the_adjustable_rail():
    assert card("fmc-ddr4-sodimm").needs_adjustable_rail
    assert not card("pmod-rs485").needs_adjustable_rail


def test_transceiver_use_is_declared():
    assert card("fmc-sfp-quad").transceivers_used == 4
    assert card("pmod-ad2").transceivers_used == 0


def test_a_card_on_a_connector_nobody_has_is_refused():
    with pytest.raises(CatalogError):
        CardSpec(
            name="mystery",
            vendor="nobody",
            connector="vpx",
            provides=frozenset({"nothing-useful"}),
            power_draw_mw=100,
        )


def test_a_card_that_provides_nothing_is_refused():
    with pytest.raises(CatalogError):
        CardSpec(
            name="blank",
            vendor="nobody",
            connector="pmod",
            provides=frozenset(),
            power_draw_mw=100,
        )


def test_capability_names_have_to_be_slugs():
    with pytest.raises(CatalogError):
        CardSpec(
            name="shouty",
            vendor="nobody",
            connector="pmod",
            provides=frozenset({"ADC 12 bit"}),
            power_draw_mw=100,
        )


def test_negative_power_is_refused():
    with pytest.raises(CatalogError):
        CardSpec(
            name="generator",
            vendor="nobody",
            connector="pmod",
            provides=frozenset({"free-energy"}),
            power_draw_mw=-1,
        )


def test_the_catalog_holds_together():
    names = [spec.name for spec in all_cards()]
    assert len(set(names)) == len(names)
    for spec in all_cards():
        assert spec.power_draw_mw > 0
        assert spec.provides
        assert spec.fits(spec.connector)
