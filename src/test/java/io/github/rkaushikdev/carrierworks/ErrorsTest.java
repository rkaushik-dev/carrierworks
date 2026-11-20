package io.github.rkaushikdev.carrierworks;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ErrorsTest {

    @Test
    void messageSurvivesStr() {
        assertThat(new BenchError("chain did not settle").getMessage()).isEqualTo("chain did not settle");
    }

    @Test
    void detailsRenderSortedAndReprQuoted() {
        BenchError err = new BenchError("bad shift", Details.of("tap", "u2", "bits", 17));
        assertThat(err.getMessage()).isEqualTo("bad shift (bits=17, tap='u2')");
    }

    @Test
    void asDictIsTheWireShape() {
        ValidationError err = new ValidationError("speed grade unknown", "part", Details.of("given", "-9"));
        assertThat(err.asMap())
                .isEqualTo(Map.of(
                        "code",
                        "validation",
                        "message",
                        "speed grade unknown",
                        "retryable",
                        false,
                        "details",
                        Map.of("field", "part", "given", "-9")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void detailsAreCopiedNotShared() {
        BenchError err = new BenchError("x", Details.of("a", 1));
        ((Map<String, Object>) err.asMap().get("details")).put("a", 2);
        assertThat(err.details().get("a")).isEqualTo(1);
    }

    @Test
    void onlyTransientErrorsAreRetryable() {
        assertThat(new TransientBenchError("adapter went away").retryable()).isTrue();
        assertThat(new ConfigurationError("two boards claim shelf 4").retryable())
                .isFalse();
        assertThat(new NotFound("board", "bx-14").retryable()).isFalse();
    }

    @Test
    void notFoundCarriesKindAndKey() {
        NotFound err = new NotFound("bitstream", "9f2c11");
        assertThat(err.kind()).isEqualTo("bitstream");
        assertThat(err.key()).isEqualTo("9f2c11");
        assertThat(err.getMessage()).isEqualTo("no bitstream known as 9f2c11 (key='9f2c11', kind='bitstream')");
    }

    @Test
    void catalogErrorIsAConfigurationError() {
        assertThat(new CatalogError("idcode listed twice")).isInstanceOf(ConfigurationError.class);
    }

    @Test
    void deviceMismatchKeepsBothSides() {
        DeviceMismatch err =
                new DeviceMismatch("image is not for this part", "xc7a100t", "xc7k325t", Details.of("board", "bx-14"));
        assertThat(err.expected()).isEqualTo("xc7a100t");
        assertThat(err.found()).isEqualTo("xc7k325t");
        assertThat(((Map<?, ?>) err.asMap().get("details")).get("board")).isEqualTo("bx-14");
        assertThat(err.code()).isEqualTo("device_mismatch");
    }

    @Test
    void validationErrorWithoutAFieldStaysClean() {
        assertThat(new ValidationError("empty chain").details()).isEmpty();
    }

    static Stream<BenchError> everythingIsABenchError() {
        return Stream.of(
                new ConfigurationError("x"),
                new ValidationError("x"),
                new NotFound("board", "y"),
                new DeviceMismatch("x", "a", "b"));
    }

    @ParameterizedTest
    @MethodSource
    void everythingIsABenchError(Object error) {
        assertThat(error).isInstanceOf(BenchError.class);
    }
}
