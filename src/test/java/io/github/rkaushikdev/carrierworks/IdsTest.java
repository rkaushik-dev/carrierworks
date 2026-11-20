package io.github.rkaushikdev.carrierworks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class IdsTest {

    @Test
    void assetTagsComeApart() {
        AssetTag tag = AssetTag.parse("BX-0142");
        assertThat(tag.prefix()).isEqualTo("bx");
        assertThat(tag.number()).isEqualTo(142);
        assertThat(tag).hasToString("bx-142");
    }

    @Test
    void assetTagsPrintPadded() {
        assertThat(AssetTag.parse("bx-9").printed()).isEqualTo("BX-0009");
        assertThat(AssetTag.parse("hil-1200").printed()).isEqualTo("HIL-1200");
    }

    @Test
    void assetTagsSortByNumberNotByText() {
        var tags = Stream.of("bx-10", "bx-9", "bx-100", "ax-50")
                .map(AssetTag::parse)
                .sorted();
        assertThat(tags.map(AssetTag::toString)).containsExactly("ax-50", "bx-9", "bx-10", "bx-100");
    }

    @Test
    void nextTagKeepsThePrefix() {
        assertThat(AssetTag.parse("bx-9").next()).isEqualTo(new AssetTag("bx", 10));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "bx", "bx-", "b-1", "board-0001", "bx-0", "bx-999999", "1-1"})
    void badAssetTagsAreRefused(String text) {
        assertThatThrownBy(() -> AssetTag.parse(text)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aHandBuiltTagIsCheckedToo() {
        assertThatThrownBy(() -> new AssetTag("board", 1)).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> new AssetTag("bx", 0)).isInstanceOf(ValidationError.class);
    }

    @Test
    void serialsNormaliseToUpperCase() {
        assertThat(Ids.adapterSerial(" 210308ab1234 ")).isEqualTo("210308AB1234");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "21030-8AB1234", "xxxxxxxxxxxxxxxxxxxx"})
    void badSerialsAreRefused(String text) {
        assertThatThrownBy(() -> Ids.adapterSerial(text)).isInstanceOf(ValidationError.class);
    }

    @ParameterizedTest
    @CsvSource({
        "radar_frontend, radar_frontend",
        "Radar Frontend, radar_frontend",
        "radar-frontend/top, radar_frontend_top",
        "'  beamformer  v2 ', beamformer_v2",
        "adc__capture, adc_capture",
    })
    void designNamesLandWhereTheToolsWouldPutThem(String given, String expected) {
        assertThat(Ids.designName(given)).isEqualTo(expected);
    }

    static Stream<String> badDesignNamesAreRefused() {
        return Stream.of("", "ab", "9lives", "___", "x".repeat(70));
    }

    @ParameterizedTest
    @MethodSource
    void badDesignNamesAreRefused(String text) {
        assertThatThrownBy(() -> Ids.designName(text)).isInstanceOf(ValidationError.class);
    }

    @ParameterizedTest
    @CsvSource({"Signal Processing, signal-processing", "rack_4, rack-4", "--qual--, qual"})
    void slugsNormalise(String given, String expected) {
        assertThat(Ids.slug(given)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-", "!!", "  "})
    void badSlugsAreRefused(String text) {
        assertThatThrownBy(() -> Ids.slug(text)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aSingleCharacterIsASlugBecauseBoardRevisionsAre() {
        assertThat(Ids.slug("A")).isEqualTo("a");
        assertThat(Ids.slug("3")).isEqualTo("3");
    }

    @Test
    void idsAreSequentialAndPrefixed() {
        IdFactory factory = new IdFactory("job");
        assertThat(factory.next()).isEqualTo("job-00001");
        assertThat(factory.next()).isEqualTo("job-00002");
        assertThat(factory.issued()).isEqualTo(2);
    }

    @Test
    void aFactoryCanCarryOnFromTheDatabase() {
        IdFactory factory = new IdFactory("lease", 4211);
        assertThat(factory.peek()).isEqualTo("lease-04211");
        assertThat(factory.next()).isEqualTo("lease-04211");
        assertThat(factory.peek()).isEqualTo("lease-04212");
    }

    @Test
    void peekingDoesNotSpendAnId() {
        IdFactory factory = new IdFactory("run");
        factory.peek();
        factory.peek();
        assertThat(factory.next()).isEqualTo("run-00001");
    }

    @Test
    void theIdSpaceCanRunOut() {
        IdFactory factory = new IdFactory("run", 999, 3);
        assertThat(factory.next()).isEqualTo("run-999");
        assertThatThrownBy(factory::next).isInstanceOf(ValidationError.class);
    }

    static Stream<Arguments> aNonsenseFactoryIsRefused() {
        return Stream.of(Arguments.of("", 1, 5), Arguments.of("job", 0, 5), Arguments.of("job", 1, 2));
    }

    @ParameterizedTest
    @MethodSource
    void aNonsenseFactoryIsRefused(String prefix, long start, int width) {
        assertThatThrownBy(() -> new IdFactory(prefix, start, width)).isInstanceOf(ValidationError.class);
    }
}
