package io.github.rkaushikdev.carrierworks.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.CatalogError;
import io.github.rkaushikdev.carrierworks.NotFound;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CardsTest {

    @Test
    void aCardIsFoundByName() {
        CardSpec spec = Cards.card("FMC-ADC-2CH-16B");
        assertThat(spec.vendor()).isEqualTo("curtiss");
        assertThat(spec.provides()).contains("adc-16bit");
        assertThat(spec.vadjMillivolts()).contains(1800);
    }

    @Test
    void anUnstockedCardIsANotFound() {
        assertThat(Cards.lookup("fmc-quad-dac")).isEmpty();
        assertThatThrownBy(() -> Cards.card("fmc-quad-dac")).isInstanceOf(NotFound.class);
    }

    @Test
    void aLowPinCountCardDropsIntoAHighPinCountCarrier() {
        assertThat(Cards.connectorAccepts("fmc-hpc", "fmc-lpc")).isTrue();
        assertThat(Cards.card("fmc-adc-2ch-16b").fits("fmc-hpc")).isTrue();
    }

    @Test
    void aHighPinCountCardDoesNotGoTheOtherWay() {
        assertThat(Cards.connectorAccepts("fmc-lpc", "fmc-hpc")).isFalse();
        assertThat(Cards.card("fmc-sfp-quad").fits("fmc-lpc")).isFalse();
    }

    @Test
    void unrelatedConnectorsNeverAcceptEachOther() {
        assertThat(Cards.connectorAccepts("pmod", "fmc-lpc")).isFalse();
        assertThat(Cards.connectorAccepts("fmc-hpc", "pmod")).isFalse();
        assertThat(Cards.connectorAccepts("pmod", "pmod")).isTrue();
    }

    @Test
    void anFmcPlusCarrierTakesEveryFmcCard() {
        assertThat(Cards.connectorAccepts("fmc-plus", "fmc-plus")).isTrue();
        assertThat(Cards.connectorAccepts("fmc-plus", "fmc-hpc")).isTrue();
        assertThat(Cards.connectorAccepts("fmc-plus", "fmc-lpc")).isTrue();
        assertThat(Cards.card("fmc-sfp-quad").fits("fmc-plus")).isTrue();
    }

    @Test
    void anFmcPlusCardNeedsAnFmcPlusCarrier() {
        CardSpec card = Cards.card("fmcp-adc-8ch-12b");
        assertThat(card.transceiversUsed()).isEqualTo(8);
        assertThat(card.fits("fmc-hpc")).isFalse();
        assertThat(card.fits("fmc-lpc")).isFalse();
        assertThat(card.fits("fmc-plus")).isTrue();
    }

    @Test
    void aPassiveCardLeavesTheAdjustableRailAlone() {
        CardSpec card = Cards.card("fmc-xm105");
        assertThat(card.needsAdjustableRail()).isFalse();
        assertThat(card.transceiversUsed()).isZero();
        assertThat(card.notes()).contains("whatever the carrier puts on vadj");
    }

    @Test
    void theCatalogueIsReadFromItsDataFile() {
        assertThat(Cards.class.getResource(Cards.RESOURCE)).isNotNull();
        assertThat(Cards.all()).extracting(CardSpec::name).contains("fmc-ddr4-sodimm", "sfp-1g-copper", "pmod-rs485");
        assertThat(Cards.card("sfp-1g-copper").vadjMillivolts()).isEmpty();
        assertThat(Cards.card("fmc-ddr4-sodimm").vadjMillivolts()).contains(1200);
        assertThat(Cards.card("pmod-ad2").tags()).isEmpty();
    }

    @Test
    void aCatalogueEntryThatIsNotACardIsRefused() {
        assertThatThrownBy(() -> Cards.cards(Map.of("cards", List.of("fmc-adc-2ch-16b"))))
                .isInstanceOf(CatalogError.class)
                .hasMessageContaining("not an object");
        assertThatThrownBy(() -> Cards.cards(Map.of())).isInstanceOf(CatalogError.class);
        assertThatThrownBy(() -> Cards.cards(Map.of(
                        "cards",
                        List.of(Map.of(
                                "name", "x",
                                "vendor", "y",
                                "connector", "pmod",
                                "provides", "gpio",
                                "power_draw_mw", 1)))))
                .isInstanceOf(CatalogError.class)
                .hasMessageContaining("a list was expected");
    }

    @Test
    void aCatalogueEntryIsCheckedLikeAnyOtherCard() {
        Map<String, Object> entry = Map.of(
                "name",
                "fmc-odd",
                "vendor",
                "y",
                "connector",
                "vita57-4",
                "provides",
                List.of("gpio"),
                "power_draw_mw",
                1);
        assertThatThrownBy(() -> Cards.cards(Map.of("cards", List.of(entry))))
                .isInstanceOf(CatalogError.class)
                .hasMessageContaining("unknown connector");
    }

    @Test
    void anUnknownCarrierConnectorIsANotFound() {
        assertThatThrownBy(() -> Cards.connectorAccepts("vita57-4", "fmc-lpc")).isInstanceOf(NotFound.class);
    }

    @Test
    void providesAllIsASubsetQuestion() {
        CardSpec spec = Cards.card("fmc-adc-4ch-14b");
        assertThat(spec.providesAll(Set.of("adc-14bit", "trigger-in"))).isTrue();
        assertThat(spec.providesAll(Set.of("adc-14bit", "dac-16bit"))).isFalse();
        assertThat(spec.providesAll(Set.of())).isTrue();
    }

    @Test
    void cardsProvidingSortsByPower() {
        assertThat(Cards.providing("adc-12bit").stream().map(CardSpec::name)).containsExactly("pmod-ad2");
        assertThat(Cards.providing("external-clock").stream().map(CardSpec::name))
                .containsExactly("fmc-adc-2ch-16b", "fmc-adc-4ch-14b");
    }

    @Test
    void askingForACapabilityNobodyProvidesGivesNothing() {
        assertThat(Cards.providing("quantum-link")).isEmpty();
    }

    @Test
    void onlyCardsWithAVadjAskForTheAdjustableRail() {
        assertThat(Cards.card("fmc-ddr4-sodimm").needsAdjustableRail()).isTrue();
        assertThat(Cards.card("pmod-rs485").needsAdjustableRail()).isFalse();
    }

    @Test
    void transceiverUseIsDeclared() {
        assertThat(Cards.card("fmc-sfp-quad").transceiversUsed()).isEqualTo(4);
        assertThat(Cards.card("pmod-ad2").transceiversUsed()).isZero();
    }

    @Test
    void aCardOnAConnectorNobodyHasIsRefused() {
        assertThatThrownBy(() -> new CardSpec("mystery", "nobody", "vpx", Set.of("nothing-useful"), 100))
                .isInstanceOf(CatalogError.class);
    }

    @Test
    void aCardThatProvidesNothingIsRefused() {
        assertThatThrownBy(() -> new CardSpec("blank", "nobody", "pmod", Set.of(), 100))
                .isInstanceOf(CatalogError.class);
    }

    @Test
    void capabilityNamesHaveToBeSlugs() {
        assertThatThrownBy(() -> new CardSpec("shouty", "nobody", "pmod", Set.of("ADC 12 bit"), 100))
                .isInstanceOf(CatalogError.class);
    }

    @Test
    void negativePowerIsRefused() {
        assertThatThrownBy(() -> new CardSpec("generator", "nobody", "pmod", Set.of("free-energy"), -1))
                .isInstanceOf(CatalogError.class);
    }

    @Test
    void theCatalogHoldsTogether() {
        List<String> names = Cards.all().stream().map(CardSpec::name).toList();
        assertThat(new HashSet<>(names)).hasSameSizeAs(names);
        for (CardSpec spec : Cards.all()) {
            assertThat(spec.powerDrawMw()).isPositive();
            assertThat(spec.provides()).isNotEmpty();
            assertThat(spec.fits(spec.connector())).isTrue();
        }
    }
}
