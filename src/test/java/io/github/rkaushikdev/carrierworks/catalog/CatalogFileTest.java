package io.github.rkaushikdev.carrierworks.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.CatalogError;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CatalogFileTest {

    @Test
    void theCarrierFileNamesEveryCarrierTheBenchKnows() {
        assertThat(CatalogFile.carriers().keySet().stream().map(String::valueOf))
                .contains("rx-carrier-a", "rx-carrier-b", "rx-carrier-c", "hil-rig-1", "tx-carrier-a");
    }

    @Test
    void aMissingFileIsACatalogueError() {
        assertThatThrownBy(() -> CatalogFile.load("/catalog/nothing.json"))
                .isInstanceOf(CatalogError.class)
                .hasMessageContaining("missing");
    }

    @Test
    void anAbsentMemberIsAnEmptyList() {
        assertThat(CatalogFile.objects(Map.of(), "rails")).isEmpty();
        assertThat(CatalogFile.texts(Map.of(), "after")).isEmpty();
        assertThat(CatalogFile.whole(Map.of(), "settle_ms", 5)).isEqualTo(5);
    }

    @Test
    void aMemberOfTheWrongShapeIsRefused() {
        assertThatThrownBy(() -> CatalogFile.objects(Map.of("rails", "vccint"), "rails"))
                .isInstanceOf(CatalogError.class)
                .hasMessageContaining("a list was expected");
        assertThatThrownBy(() -> CatalogFile.texts(Map.of("after", 3), "after")).isInstanceOf(CatalogError.class);
        assertThatThrownBy(() -> CatalogFile.objects(Map.of("rails", List.of(1)), "rails"))
                .isInstanceOf(CatalogError.class)
                .hasMessageContaining("not an object");
    }

    @Test
    void wholeNumbersMayBeWrittenAsText() {
        assertThat(CatalogFile.whole(Map.of("mv", "1_800"), "mv", 0)).isEqualTo(1800);
    }
}
