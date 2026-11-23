package io.github.rkaushikdev.carrierworks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CapabilitiesTest {

    private static CapabilityProfile profile() {
        return CapabilityProfile.build(
                Set.of("adc-16bit", "adc-2ch", "external-clock"),
                Map.of("transceivers", 4, "block-ram", 135),
                Map.of("family", "artix7", "channel", "qual"));
    }

    @Test
    void aBareTokenAsksForACapability() {
        assertThat(Requirement.parse("adc-16bit").satisfiedBy(profile())).isTrue();
        assertThat(Requirement.parse("ddr4-sodimm").satisfiedBy(profile())).isFalse();
    }

    @Test
    void aBangAsksForTheAbsenceOfOne() {
        assertThat(Requirement.parse("!ddr4-sodimm").satisfiedBy(profile())).isTrue();
        assertThat(Requirement.parse("!adc-2ch").satisfiedBy(profile())).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
        "transceivers>=4, true",
        "transceivers>=5, false",
        "transceivers>3, true",
        "transceivers>4, false",
        "transceivers<=4, true",
        "transceivers<4, false",
        "block-ram>=100, true",
    })
    void comparisonsReadTheCounts(String text, boolean met) {
        assertThat(Requirement.parse(text).satisfiedBy(profile())).isEqualTo(met);
    }

    @Test
    void aMissingCountIsZeroRatherThanAnError() {
        assertThat(Requirement.parse("gth-lanes<=0").satisfiedBy(profile())).isTrue();
        assertThat(Requirement.parse("gth-lanes>=1").satisfiedBy(profile())).isFalse();
    }

    @Test
    void attributesCompareByEquality() {
        assertThat(Requirement.parse("family=artix7").satisfiedBy(profile())).isTrue();
        assertThat(Requirement.parse("family=kintex7").satisfiedBy(profile())).isFalse();
        assertThat(Requirement.parse("family!=kintex7").satisfiedBy(profile())).isTrue();
        assertThat(Requirement.parse("family!=artix7").satisfiedBy(profile())).isFalse();
    }

    @Test
    void aMissingAttributeFailsEqualityAndPassesInequality() {
        assertThat(Requirement.parse("vendor=digilent").satisfiedBy(profile())).isFalse();
        assertThat(Requirement.parse("vendor!=digilent").satisfiedBy(profile())).isTrue();
    }

    @Test
    void whitespaceAroundAnOperatorIsAllowed() {
        assertThat(Requirement.parse("transceivers >= 4").satisfiedBy(profile()))
                .isTrue();
        assertThat(Requirement.parse(" family = artix7 ").satisfiedBy(profile()))
                .isTrue();
    }

    @Test
    void requirementsKeepTheTextTheyCameFrom() {
        assertThat(Requirement.parse("  ADC-16BIT ")).hasToString("adc-16bit");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "transceivers>=lots", "transceivers>=4.5"})
    void nonsenseRequirementsAreRefused(String text) {
        assertThatThrownBy(() -> Requirement.parse(text)).isInstanceOf(ValidationError.class);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "ddr4-sodimm | no ddr4-sodimm",
                "!adc-2ch | has adc-2ch and the job asked for a board without it",
                "transceivers>=8 | transceivers is 4, wanted >=8",
                "family=kintex7 | family is artix7, wanted kintex7",
                "vendor=digilent | vendor is nothing, wanted digilent",
                "family!=artix7 | family is artix7 and the job asked for anything else",
            })
    void everyRefusalSaysWhy(String text, String reason) {
        assertThat(Requirement.parse(text).explain(profile())).contains(reason);
    }

    private static CapabilityProfile graded(String grade) {
        return CapabilityProfile.build(
                Set.of(),
                Map.of(Requirement.SPEED_RANK, Requirement.gradeRank(grade).orElseThrow()),
                Map.of("speed", grade));
    }

    @ParameterizedTest
    @CsvSource({
        "speed>=2, 3, true",
        "speed>=2, 2, true",
        "speed>=2, 2l, false",
        "speed>=2, 1, false",
        "speed>=2l, 2l, true",
        "speed>1l, 1, true",
        "speed<2, 2l, true",
        "speed<=1, 1l, true",
        "speed>=-2, 2, true"
    })
    void aSpeedComparisonRanksGradesWithTheLowPowerBinsBetween(String text, String grade, boolean met) {
        Requirement requirement = Requirement.parse(text);
        assertThat(requirement.kind()).isEqualTo(Requirement.Kind.SPEED);
        assertThat(requirement.satisfiedBy(graded(grade))).isEqualTo(met);
    }

    @Test
    void aSlowerPartSaysWhichGradeItIs() {
        assertThat(Requirement.parse("speed>=2").explain(graded("1"))).contains("speed is -1, wanted >=-2");
    }

    @Test
    void aSpeedEqualityIsStillAnAttribute() {
        Requirement requirement = Requirement.parse("speed=2l");
        assertThat(requirement.kind()).isEqualTo(Requirement.Kind.ATTRIBUTE);
        assertThat(requirement.satisfiedBy(graded("2l"))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"speed>=fast", "speed>=12", "speed>=2x"})
    void aSpeedComparisonNeedsAGrade(String text) {
        assertThatThrownBy(() -> Requirement.parse(text))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("needs a grade");
    }

    @Test
    void gradesRankTheWayThePartNumberDoes() {
        assertThat(Requirement.gradeRank("3")).hasValue(6);
        assertThat(Requirement.gradeRank("2L")).hasValue(3);
        assertThat(Requirement.gradeRank("-1")).hasValue(2);
        assertThat(Requirement.gradeRank("")).isEmpty();
    }

    @Test
    void aMetRequirementExplainsNothing() {
        assertThat(Requirement.parse("adc-16bit").explain(profile())).isEmpty();
    }

    @Test
    void aSetHoldsOnlyWhenEverythingDoes() {
        RequirementSet wanted = RequirementSet.parse(List.of("adc-16bit", "transceivers>=4", "family=artix7"));
        assertThat(wanted.satisfiedBy(profile())).isTrue();
        assertThat(wanted.unmet(profile())).isEmpty();
        assertThat(wanted.size()).isEqualTo(3);
    }

    @Test
    void aSetListsEveryUnmetRequirementNotJustTheFirst() {
        RequirementSet wanted = RequirementSet.parse(List.of("ddr4-sodimm", "transceivers>=8", "adc-16bit"));
        assertThat(wanted.unmet(profile()).stream().map(Requirement::toString))
                .containsExactly("ddr4-sodimm", "transceivers>=8");
        assertThat(wanted.whyNot(profile())).isEqualTo("no ddr4-sodimm; transceivers is 4, wanted >=8");
    }

    @Test
    void aSetParsesFromOneStringToo() {
        RequirementSet wanted = RequirementSet.parse("adc-16bit, transceivers>=2\nfamily=artix7");
        assertThat(wanted.size()).isEqualTo(3);
        assertThat(wanted.satisfiedBy(profile())).isTrue();
    }

    @Test
    void anEmptySetIsMetByAnything() {
        assertThat(RequirementSet.parse(List.of()).satisfiedBy(CapabilityProfile.empty()))
                .isTrue();
        assertThat(RequirementSet.parse(List.of()).whyNot(CapabilityProfile.empty()))
                .isEmpty();
    }

    @Test
    void aSetIterates() {
        RequirementSet wanted = RequirementSet.parse(List.of("adc-16bit", "adc-2ch"));
        assertThat(StreamSupport.stream(wanted.spliterator(), false).map(Requirement::key))
                .containsExactly("adc-16bit", "adc-2ch");
    }

    @Test
    void foldingInACardAddsItsCapabilities() {
        CapabilityProfile base = CapabilityProfile.build(Set.of("jtag"), Map.of("transceivers", 4), Map.of());
        CapabilityProfile withCard = base.withTokens(Set.of("sfp-4port", "ethernet-10g"));
        assertThat(Requirement.parse("sfp-4port").satisfiedBy(withCard)).isTrue();
        assertThat(Requirement.parse("jtag").satisfiedBy(withCard)).isTrue();
        assertThat(withCard.number("transceivers")).isEqualTo(4);
        assertThat(Requirement.parse("sfp-4port").satisfiedBy(base)).isFalse();
    }

    @Test
    void buildingAProfileNormalisesItsKeys() {
        CapabilityProfile built =
                CapabilityProfile.build(Set.of("ADC 16bit"), Map.of("Block RAM", 135), Map.of("Family", "Artix7"));
        assertThat(Requirement.parse("adc-16bit").satisfiedBy(built)).isTrue();
        assertThat(built.number("block-ram")).isEqualTo(135);
        assertThat(built.attribute("family")).contains("artix7");
    }
}
