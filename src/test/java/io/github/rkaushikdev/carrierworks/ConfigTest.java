package io.github.rkaushikdev.carrierworks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigTest {

    @Test
    void theDefaultsAreAWorkingBench() {
        BenchSettings settings = BenchSettings.defaults();
        assertThat(settings.site()).isEqualTo("lab-a");
        assertThat(settings.defaultLeaseMinutes()).isEqualTo(60);
        assertThat(settings.verifyAfterProgram()).isTrue();
        assertThat(settings.leaseCeilingSeconds()).isEqualTo(480 * 60 + 120);
    }

    @Test
    void theEnvironmentIsReadWithThePrefix() {
        BenchSettings settings = BenchSettings.fromEnv(Map.of(
                "FPGA_SITE", "Lab B",
                "FPGA_DEFAULT_LEASE_MINUTES", "30",
                "FPGA_VERIFY_AFTER_PROGRAM", "no",
                "PATH", "/usr/bin",
                "SITE", "somewhere else"));
        assertThat(settings.site()).isEqualTo("lab-b");
        assertThat(settings.defaultLeaseMinutes()).isEqualTo(30);
        assertThat(settings.verifyAfterProgram()).isFalse();
    }

    @Test
    void anythingUnsetKeepsItsDefault() {
        BenchSettings settings = BenchSettings.fromEnv(Map.of("FPGA_SITE", "lab-c"));
        assertThat(settings.maxLeaseMinutes())
                .isEqualTo(BenchSettings.defaults().maxLeaseMinutes());
        assertThat(settings.databaseUrl()).isEqualTo(BenchSettings.defaults().databaseUrl());
    }

    @Test
    void anEmptyValueIsTreatedAsUnset() {
        BenchSettings settings = BenchSettings.fromEnv(Map.of("FPGA_DEFAULT_LEASE_MINUTES", "  "));
        assertThat(settings.defaultLeaseMinutes()).isEqualTo(60);
    }

    @Test
    void pathsComeThroughAsPaths() {
        BenchSettings settings = BenchSettings.fromEnv(Map.of("FPGA_ARTIFACT_ROOT", "/srv/bitstreams"));
        assertThat(settings.artifactRoot()).isEqualTo(Path.of("/srv/bitstreams"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"yes", "true", "1", "ON"})
    void theWaysPeopleWriteYes(String text) {
        assertThat(BenchSettings.fromEnv(Map.of("FPGA_VERIFY_AFTER_PROGRAM", text))
                        .verifyAfterProgram())
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"no", "false", "0", "OFF"})
    void theWaysPeopleWriteNo(String text) {
        assertThat(BenchSettings.fromEnv(Map.of("FPGA_VERIFY_AFTER_PROGRAM", text))
                        .verifyAfterProgram())
                .isFalse();
    }

    @Test
    void aValueThatIsNeitherIsRefused() {
        ConfigurationError caught = catchThrowableOfType(
                ConfigurationError.class, () -> BenchSettings.fromEnv(Map.of("FPGA_VERIFY_AFTER_PROGRAM", "maybe")));
        assertThat(caught.details()).containsEntry("setting", "verify_after_program");
    }

    @Test
    void aNumberThatIsNotOneIsRefused() {
        assertThatThrownBy(() -> BenchSettings.fromEnv(Map.of("FPGA_PROGRAM_ATTEMPTS", "three")))
                .isInstanceOf(ConfigurationError.class);
    }

    @Test
    void aLeaseThatExpiresImmediatelyIsRefused() {
        assertThatThrownBy(() -> BenchSettings.builder().defaultLeaseMinutes(0).build())
                .isInstanceOf(ConfigurationError.class);
    }

    @Test
    void aCeilingBelowTheDefaultIsRefused() {
        ConfigurationError caught = catchThrowableOfType(ConfigurationError.class, () -> BenchSettings.builder()
                .defaultLeaseMinutes(120)
                .maxLeaseMinutes(60)
                .build());
        assertThat(caught.details()).containsEntry("ceiling", 60);
    }

    @Test
    void aSampleThatGoesStaleBeforeTheNextPollIsRefused() {
        assertThatThrownBy(() -> BenchSettings.builder()
                        .thermalPollSeconds(60)
                        .thermalStaleSeconds(30)
                        .build())
                .isInstanceOf(ConfigurationError.class);
        assertThatThrownBy(() -> BenchSettings.builder().thermalPollSeconds(0).build())
                .isInstanceOf(ConfigurationError.class);
    }

    static Stream<Arguments> settingsOutsideTheirRangeAreRefused() {
        return Stream.of(
                Arguments.of("program_attempts", (UnaryOperator<BenchSettings.Builder>) b -> b.programAttempts(0)),
                Arguments.of("program_attempts", (UnaryOperator<BenchSettings.Builder>) b -> b.programAttempts(11)),
                Arguments.of("program_backoff_seconds", (UnaryOperator<BenchSettings.Builder>)
                        b -> b.programBackoffSeconds(-1)),
                Arguments.of(
                        "lease_grace_seconds", (UnaryOperator<BenchSettings.Builder>) b -> b.leaseGraceSeconds(-1)),
                Arguments.of("adapter_clock_ceiling_hz", (UnaryOperator<BenchSettings.Builder>)
                        b -> b.adapterClockCeilingHz(1000)),
                Arguments.of("adapter_clock_ceiling_hz", (UnaryOperator<BenchSettings.Builder>)
                        b -> b.adapterClockCeilingHz(90_000_000)),
                Arguments.of(
                        "utc_offset_minutes", (UnaryOperator<BenchSettings.Builder>) b -> b.utcOffsetMinutes(-900)),
                Arguments.of("api_key_header", (UnaryOperator<BenchSettings.Builder>) b -> b.apiKeyHeader("  ")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void settingsOutsideTheirRangeAreRefused(String field, UnaryOperator<BenchSettings.Builder> change) {
        assertThatThrownBy(() -> change.apply(BenchSettings.builder()).build()).isInstanceOf(ConfigurationError.class);
    }

    @Test
    void overridingKeepsTheChecks() {
        BenchSettings settings = BenchSettings.defaults();
        BenchSettings tighter = settings.override(Map.of("default_lease_minutes", 15));
        assertThat(tighter.defaultLeaseMinutes()).isEqualTo(15);
        assertThat(settings.defaultLeaseMinutes()).isEqualTo(60);
        assertThatThrownBy(() -> settings.override(Map.of("max_lease_minutes", 1)))
                .isInstanceOf(ConfigurationError.class);
    }

    @Test
    void overridingSomethingThatDoesNotExistSaysSo() {
        ConfigurationError caught = catchThrowableOfType(
                ConfigurationError.class, () -> BenchSettings.defaults().override(Map.of("lease_minutes", 15)));
        assertThat(caught.details()).containsEntry("setting", "lease_minutes");
    }

    /**
     * The original refused assignment to a field at run time. A record has no setters and every
     * component field is final, so the same guarantee is checked here by reflection.
     */
    @Test
    void settingsAreFrozen() {
        assertThat(BenchSettings.class.isRecord()).isTrue();
        assertThat(Arrays.stream(BenchSettings.class.getDeclaredFields())
                        .filter(field -> !Modifier.isStatic(field.getModifiers()))
                        .map(Field::getModifiers))
                .allMatch(Modifier::isFinal);
        assertThat(Arrays.stream(BenchSettings.class.getMethods()).map(method -> method.getName()))
                .noneMatch(name -> name.startsWith("set"));
    }
}
