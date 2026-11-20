package io.github.rkaushikdev.carrierworks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LocationsTest {

    @ParameterizedTest
    @ValueSource(strings = {"rack-2/shelf-04/slot-3", "rack-2:4:3", "rack-2/4/3", " RACK-2 / SHELF-4 / SLOT-3 "})
    void locationsParseHoweverTheyWereTyped(String text) {
        assertThat(BenchLocation.parse(text)).isEqualTo(new BenchLocation("rack-2", 4, 3));
    }

    @Test
    void locationsPrintPaddedAndLabelled() {
        assertThat(new BenchLocation("rack-2", 4, 3)).hasToString("rack-2/shelf-04/slot-3");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "rack-2", "rack-2/13/1", "rack-2/4/7", "rack-2/0/1", "2/4/3x"})
    void badLocationsAreRefused(String text) {
        assertThatThrownBy(() -> BenchLocation.parse(text)).isInstanceOf(ValidationError.class);
    }

    @Test
    void rackNamesAreNormalisedOnTheWayIn() {
        assertThat(new BenchLocation("Rack 2", 4, 3).rack()).isEqualTo("rack-2");
    }

    @Test
    void locationsSortRackThenShelfThenSlot() {
        List<BenchLocation> locations = List.of(
                new BenchLocation("rack-2", 4, 3),
                new BenchLocation("rack-1", 12, 6),
                new BenchLocation("rack-2", 4, 1),
                new BenchLocation("rack-2", 1, 6));
        assertThat(locations.stream().sorted().map(BenchLocation::toString))
                .containsExactly(
                        "rack-1/shelf-12/slot-6",
                        "rack-2/shelf-01/slot-6",
                        "rack-2/shelf-04/slot-1",
                        "rack-2/shelf-04/slot-3");
    }

    @Test
    void sameShelfIgnoresTheSlot() {
        assertThat(new BenchLocation("rack-2", 4, 3).sameShelf(new BenchLocation("rack-2", 4, 6)))
                .isTrue();
        assertThat(new BenchLocation("rack-2", 4, 3).sameShelf(new BenchLocation("rack-3", 4, 3)))
                .isFalse();
    }

    @Test
    void aboveIsAboutHeightNotDistance() {
        BenchLocation high = new BenchLocation("rack-2", 9, 1);
        BenchLocation low = new BenchLocation("rack-2", 4, 1);
        assertThat(high.isAbove(low)).isTrue();
        assertThat(low.isAbove(high)).isFalse();
        assertThat(high.isAbove(new BenchLocation("rack-3", 4, 1))).isFalse();
    }

    @Test
    void shelvesAboveStopAtTheTopOfTheRack() {
        assertThat(new BenchLocation("rack-2", 11, 3).shelvesAbove(4))
                .containsExactly(new BenchLocation("rack-2", 12, 3));
        assertThat(new BenchLocation("rack-2", 12, 3).shelvesAbove(2)).isEmpty();
    }

    @Test
    void askingForNoShelvesGivesNone() {
        assertThat(new BenchLocation("rack-2", 4, 3).shelvesAbove(0)).isEmpty();
        assertThatThrownBy(() -> new BenchLocation("rack-2", 4, 3).shelvesAbove(-1))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void downwindWalksUpTheColumnNearestFirst() {
        BenchLocation source = new BenchLocation("rack-2", 4, 3);
        Map<String, BenchLocation> candidates = new LinkedHashMap<>();
        candidates.put("bx-1", new BenchLocation("rack-2", 6, 3));
        candidates.put("bx-2", new BenchLocation("rack-2", 5, 3));
        candidates.put("bx-3", new BenchLocation("rack-2", 3, 3));
        candidates.put("bx-4", new BenchLocation("rack-2", 5, 4));
        candidates.put("bx-5", new BenchLocation("rack-3", 5, 3));
        assertThat(Locations.downwindOf(source, candidates)).containsExactly("bx-2", "bx-1");
    }

    @Test
    void reachLimitsHowFarTheWarningCarries() {
        BenchLocation source = new BenchLocation("rack-2", 4, 3);
        Map<String, BenchLocation> candidates = Map.of("bx-1", new BenchLocation("rack-2", 6, 3));
        assertThat(Locations.downwindOf(source, candidates, 1)).isEmpty();
        assertThat(Locations.downwindOf(source, candidates, 2)).containsExactly("bx-1");
    }

    @Test
    void nothingDownwindOfAnEmptyRack() {
        assertThat(Locations.downwindOf(new BenchLocation("rack-2", 4, 3), Map.of()))
                .isEmpty();
    }

    @Test
    void pduChannelsParseAndPrint() {
        assertThat(PduChannel.parse("pdu-a/outlet-7")).isEqualTo(new PduChannel("pdu-a", 7));
        assertThat(PduChannel.parse("pdu-a:7")).isEqualTo(new PduChannel("pdu-a", 7));
        assertThat(new PduChannel("pdu-a", 7)).hasToString("pdu-a/outlet-07");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "pdu-a", "pdu-a/0", "pdu-a/25", "pdu-a/outlet"})
    void badPduChannelsAreRefused(String text) {
        assertThatThrownBy(() -> PduChannel.parse(text)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aBoardAndItsOutletAreSeparateAddresses() {
        BenchLocation location = new BenchLocation("rack-2", 4, 3);
        PduChannel channel = new PduChannel("pdu-c", 11);
        assertThat(location.rack()).isNotEqualTo(channel.pdu());
    }
}
