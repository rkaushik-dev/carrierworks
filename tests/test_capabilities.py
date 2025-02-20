import pytest

from fpga.capabilities import CapabilityProfile, Requirement, RequirementSet
from fpga.errors import ValidationError


def profile():
    return CapabilityProfile.build(
        tokens={"adc-16bit", "adc-2ch", "external-clock"},
        numbers={"transceivers": 4, "block-ram": 135},
        attributes={"family": "artix7", "channel": "qual"},
    )


def test_a_bare_token_asks_for_a_capability():
    assert Requirement.parse("adc-16bit").satisfied_by(profile())
    assert not Requirement.parse("ddr4-sodimm").satisfied_by(profile())


def test_a_bang_asks_for_the_absence_of_one():
    assert Requirement.parse("!ddr4-sodimm").satisfied_by(profile())
    assert not Requirement.parse("!adc-2ch").satisfied_by(profile())


@pytest.mark.parametrize(
    ("text", "met"),
    [
        ("transceivers>=4", True),
        ("transceivers>=5", False),
        ("transceivers>3", True),
        ("transceivers>4", False),
        ("transceivers<=4", True),
        ("transceivers<4", False),
        ("block-ram>=100", True),
    ],
)
def test_comparisons_read_the_counts(text, met):
    assert Requirement.parse(text).satisfied_by(profile()) is met


def test_a_missing_count_is_zero_rather_than_an_error():
    assert Requirement.parse("gth-lanes<=0").satisfied_by(profile())
    assert not Requirement.parse("gth-lanes>=1").satisfied_by(profile())


def test_attributes_compare_by_equality():
    assert Requirement.parse("family=artix7").satisfied_by(profile())
    assert not Requirement.parse("family=kintex7").satisfied_by(profile())
    assert Requirement.parse("family!=kintex7").satisfied_by(profile())
    assert not Requirement.parse("family!=artix7").satisfied_by(profile())


def test_a_missing_attribute_fails_equality_and_passes_inequality():
    assert not Requirement.parse("vendor=digilent").satisfied_by(profile())
    assert Requirement.parse("vendor!=digilent").satisfied_by(profile())


def test_whitespace_around_an_operator_is_allowed():
    assert Requirement.parse("transceivers >= 4").satisfied_by(profile())
    assert Requirement.parse(" family = artix7 ").satisfied_by(profile())


def test_requirements_keep_the_text_they_came_from():
    assert str(Requirement.parse("  ADC-16BIT ")) == "adc-16bit"


@pytest.mark.parametrize("text", ["", "   ", "transceivers>=lots", "transceivers>=4.5"])
def test_nonsense_requirements_are_refused(text):
    with pytest.raises(ValidationError):
        Requirement.parse(text)


@pytest.mark.parametrize(
    ("text", "reason"),
    [
        ("ddr4-sodimm", "no ddr4-sodimm"),
        ("!adc-2ch", "has adc-2ch and the job asked for a board without it"),
        ("transceivers>=8", "transceivers is 4, wanted >=8"),
        ("family=kintex7", "family is artix7, wanted kintex7"),
        ("vendor=digilent", "vendor is nothing, wanted digilent"),
        ("family!=artix7", "family is artix7 and the job asked for anything else"),
    ],
)
def test_every_refusal_says_why(text, reason):
    assert Requirement.parse(text).explain(profile()) == reason


def test_a_met_requirement_explains_nothing():
    assert Requirement.parse("adc-16bit").explain(profile()) is None


def test_a_set_holds_only_when_everything_does():
    wanted = RequirementSet.parse(["adc-16bit", "transceivers>=4", "family=artix7"])
    assert wanted.satisfied_by(profile())
    assert wanted.unmet(profile()) == ()
    assert len(wanted) == 3


def test_a_set_lists_every_unmet_requirement_not_just_the_first():
    wanted = RequirementSet.parse(["ddr4-sodimm", "transceivers>=8", "adc-16bit"])
    unmet = [str(req) for req in wanted.unmet(profile())]
    assert unmet == ["ddr4-sodimm", "transceivers>=8"]
    assert wanted.why_not(profile()) == "no ddr4-sodimm; transceivers is 4, wanted >=8"


def test_a_set_parses_from_one_string_too():
    wanted = RequirementSet.parse("adc-16bit, transceivers>=2\nfamily=artix7")
    assert len(wanted) == 3
    assert wanted.satisfied_by(profile())


def test_an_empty_set_is_met_by_anything():
    assert RequirementSet.parse([]).satisfied_by(CapabilityProfile())
    assert RequirementSet.parse([]).why_not(CapabilityProfile()) == ""


def test_a_set_iterates():
    wanted = RequirementSet.parse(["adc-16bit", "adc-2ch"])
    assert [req.key for req in wanted] == ["adc-16bit", "adc-2ch"]


def test_folding_in_a_card_adds_its_capabilities():
    base = CapabilityProfile.build(tokens={"jtag"}, numbers={"transceivers": 4})
    with_card = base.with_tokens({"sfp-4port", "ethernet-10g"})
    assert Requirement.parse("sfp-4port").satisfied_by(with_card)
    assert Requirement.parse("jtag").satisfied_by(with_card)
    assert with_card.number("transceivers") == 4
    assert not Requirement.parse("sfp-4port").satisfied_by(base)


def test_building_a_profile_normalises_its_keys():
    built = CapabilityProfile.build(
        tokens={"ADC 16bit"},
        numbers={"Block RAM": 135},
        attributes={"Family": "Artix7"},
    )
    assert Requirement.parse("adc-16bit").satisfied_by(built)
    assert built.number("block-ram") == 135
    assert built.attribute("family") == "artix7"
