package io.github.rkaushikdev.carrierworks.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.carrierworks.CatalogError;
import io.github.rkaushikdev.carrierworks.NotFound;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CatalogIndexTest {

    private record Widget(String name, int code, String colour) {}

    private static CatalogIndex<Widget> widgets() {
        return new CatalogIndex<>(
                "widget",
                List.of(new Widget("left", 1, "red"), new Widget("right", 2, "blue"), new Widget("middle", 3, "red")),
                Widget::name,
                Map.of("code", Widget::code));
    }

    @Test
    void entriesAreFoundByName() {
        assertThat(widgets().require("left").code()).isEqualTo(1);
        assertThat(widgets().lookup("RIGHT ").orElseThrow().colour()).isEqualTo("blue");
    }

    @Test
    void aMissIsNothingOrANotFound() {
        assertThat(widgets().lookup("nowhere")).isEmpty();
        NotFound caught = catchThrowableOfType(NotFound.class, () -> widgets().require("nowhere"));
        assertThat(caught.kind()).isEqualTo("widget");
        assertThat(caught.key()).isEqualTo("nowhere");
    }

    @Test
    void aSecondaryIndexFindsEntriesToo() {
        assertThat(widgets().find("code", 2).orElseThrow().name()).isEqualTo("right");
        assertThat(widgets().find("code", 9)).isEmpty();
    }

    @Test
    void aSecondaryMissNamesTheIndexAndTheValueAskedFor() {
        NotFound caught = catchThrowableOfType(NotFound.class, () -> widgets().requireBy("code", 9, "0x09"));
        assertThat(caught.kind()).isEqualTo("widget code");
        assertThat(caught.key()).isEqualTo("0x09");
    }

    @Test
    void anIndexNobodyBuiltIsACatalogError() {
        assertThatThrownBy(() -> widgets().find("colour", "red")).isInstanceOf(CatalogError.class);
    }

    @Test
    void twoEntriesWithOneNameAreRefusedAtBuildTime() {
        assertThatThrownBy(() -> new CatalogIndex<>(
                        "widget", List.of(new Widget("left", 1, "red"), new Widget("left", 2, "blue")), Widget::name))
                .isInstanceOf(CatalogError.class);
    }

    @Test
    void twoEntriesSharingASecondaryValueAreRefused() {
        CatalogError caught = catchThrowableOfType(
                CatalogError.class,
                () -> new CatalogIndex<>(
                        "widget",
                        List.of(new Widget("left", 1, "red"), new Widget("right", 1, "blue")),
                        Widget::name,
                        Map.of("code", Widget::code)));
        assertThat(caught.message()).contains("share a code");
    }

    @Test
    void filteringKeepsTheCatalogOrder() {
        assertThat(widgets().where(w -> w.colour().equals("red")).stream().map(Widget::name))
                .containsExactly("left", "middle");
    }

    @Test
    void theCatalogReportsItsOwnShape() {
        CatalogIndex<Widget> catalog = widgets();
        assertThat(catalog.size()).isEqualTo(3);
        assertThat(catalog.names()).containsExactly("left", "middle", "right");
        assertThat(catalog.contains("LEFT")).isTrue();
        assertThat(catalog.contains("nowhere")).isFalse();
        assertThat(catalog.contains(4)).isFalse();
    }

    @Test
    void theDeviceCatalogUsesIt() {
        assertThat(Devices.DEVICES.kind()).isEqualTo("device");
        assertThat(Devices.DEVICES.require("xc7a100t").luts()).isEqualTo(63400);
        assertThat(Devices.DEVICES.find("idcode", 0x03631093L).orElseThrow().device())
                .isEqualTo("xc7a100t");
        assertThat(Devices.DEVICES.size()).isEqualTo(Devices.DEVICES.all().size());
    }

    @Test
    void theFlashCatalogUsesIt() {
        assertThat(FlashParts.FLASH_PARTS.require("n25q128a13").vendor()).isEqualTo("micron");
        assertThat(FlashParts.FLASH_PARTS
                        .find("jedec id", 0x20BA18)
                        .orElseThrow()
                        .name())
                .isEqualTo("n25q128a13");
    }
}
