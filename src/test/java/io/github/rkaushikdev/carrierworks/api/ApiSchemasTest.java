package io.github.rkaushikdev.carrierworks.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.inventory.BoardState;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.jobs.JobMode;
import io.github.rkaushikdev.carrierworks.jobs.ProgramJob;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import io.github.rkaushikdev.carrierworks.testroutes.DependencyRoutes;
import io.github.rkaushikdev.carrierworks.util.Times;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ApiSchemasTest {

    private static final Instant WHEN = Instant.parse("2025-12-24T09:00:00Z");

    private static ValidatorFactory validation;
    private static TestBench bench;

    @BeforeAll
    static void start() {
        validation = Validation.buildDefaultValidatorFactory();
        bench = TestBench.start(BenchSettings.builder().site("lab-c").build(), WHEN, DependencyRoutes.class);
    }

    @AfterAll
    static void stop() {
        bench.close();
        validation.close();
    }

    @BeforeEach
    void fresh() {
        bench.reset(WHEN);
    }

    /** A shape read the way a request body is: bound from its JSON fields, then checked. */
    private static <T> T read(Map<String, ?> fields, Class<T> shape) {
        T value = ApiJson.mapper().convertValue(fields, shape);
        Validator validator = validation.getValidator();
        Set<ConstraintViolation<T>> problems = validator.validate(value);
        if (!problems.isEmpty()) {
            throw new ConstraintViolationException(problems);
        }
        return value;
    }

    private static Lease lease() {
        return Lease.builder("lease-00001", "bx-14", "team-a", "capture", WHEN, WHEN.plus(Duration.ofHours(1)))
                .build();
    }

    @Test
    void aBoardGoesOutWithWhatADashboardNeeds() {
        BoardOut out = BoardOut.of(BoardSetup.kintex("bx-14").build(), new BoardState(BoardStatus.LEASED, WHEN));
        assertThat(out.asset()).isEqualTo("bx-14");
        assertThat(out.device()).isEqualTo("xc7k325t");
        assertThat(out.family()).isEqualTo("kintex7");
        assertThat(out.location()).isEqualTo("rack-2/shelf-04/slot-3");
        assertThat(out.cards()).containsExactly("fmc-adc-4ch-14b");
        assertThat(out.transceiversFree()).isEqualTo(12);
        assertThat(out.state()).isEqualTo("leased");
    }

    @Test
    void aBoardWithNoStateSaysUnknownRatherThanGuessing() {
        BoardOut out = BoardOut.of(BoardSetup.kintex("bx-14").build());
        assertThat(out.state()).isEqualTo("unknown");
        assertThat(out.stateSince()).isEmpty();
    }

    @Test
    void aBoardWithoutACableOrAnOutletComesBackWithNulls() {
        BoardOut out =
                BoardOut.of(BoardSetup.kintex("bx-14").noCable().noOutlet().build());
        assertThat(out.adapter()).isEmpty();
        assertThat(out.pdu()).isEmpty();
        Map<String, Object> written = Http.JSON.convertValue(out, new TypeReference<Map<String, Object>>() {});
        assertThat(written).containsEntry("adapter", null).containsEntry("pdu", null);
    }

    @Test
    void aBoardArrivesAsTheStringsPeopleWrite() {
        BoardIn incoming = read(
                Map.of(
                        "asset",
                        "BX-0014",
                        "model",
                        "rx-carrier-b",
                        "part",
                        "XC7K325T-2FFG900I",
                        "flash",
                        "n25q128a13",
                        "location",
                        "rack-2/shelf-04/slot-3",
                        "slots",
                        List.of(Map.of("name", "fmc0", "connector", "fmc-hpc", "fitted", "fmc-adc-4ch-14b"))),
                BoardIn.class);
        assertThat(incoming.asset()).isEqualTo("BX-0014");
        assertThat(incoming.slots().get(0).fitted()).contains("fmc-adc-4ch-14b");
        assertThat(incoming.vadjMillivolts()).isEqualTo(1800);
    }

    @Test
    void aBoardMissingAFieldIsRefusedByTheSchema() {
        ConstraintViolationException refused = catchThrowableOfType(
                ConstraintViolationException.class,
                () -> read(Map.of("asset", "bx-14", "model", "rx-carrier-b"), BoardIn.class));
        assertThat(refused.getConstraintViolations())
                .extracting(problem -> problem.getPropertyPath().toString())
                .containsExactlyInAnyOrder("part", "flash", "location");
        assertThat(refused.getConstraintViolations())
                .extracting(ConstraintViolation::getMessage)
                .containsOnly("a value is required");
    }

    @Test
    void aStateChangeCarriesItsReason() {
        StateIn change =
                read(Map.of("state", "faulted", "reason", "chain read as all ones", "actor", "nightly"), StateIn.class);
        assertThat(change.reason()).isEqualTo("chain read as all ones");
        assertThat(read(Map.of("state", "available"), StateIn.class).reason()).isEmpty();
    }

    @Test
    void aLeaseGoesOutWhole() {
        LeaseOut out = LeaseOut.of(lease());
        assertThat(out.id()).isEqualTo("lease-00001");
        assertThat(out.expiresAt()).isEqualTo(WHEN.plus(Duration.ofHours(1)));
        assertThat(out.releasedAt()).isEmpty();
    }

    @Test
    void aReleasedLeaseCarriesItsReason() {
        LeaseOut out = LeaseOut.of(lease().release(WHEN.plus(Duration.ofMinutes(10)), "finished"));
        assertThat(out.releasedAt()).isPresent();
        assertThat(out.releaseReason()).isEqualTo("finished");
    }

    @Test
    void aLeaseRequestDefaultsToTheMiddlePriority() {
        LeaseIn asked = read(Map.of("holder", "team-a", "purpose", "capture"), LeaseIn.class);
        assertThat(asked.priority()).isEqualTo(5);
        assertThat(asked.minutes()).isEmpty();
        assertThat(asked.preemptible()).isTrue();
    }

    @Test
    void aJobGoesOutWithItsError() {
        ProgramJob job = ProgramJob.builder("job-00001", "bx-14", "ab".repeat(32), JobMode.JTAG, "team-a", WHEN)
                .build();
        ProgramJob failed = job.start(WHEN).failed(WHEN.plus(Duration.ofMinutes(1)), "cable", "chain");
        JobOut out = JobOut.of(failed);
        assertThat(out.state()).isEqualTo("failed");
        assertThat(out.errorCode()).isEqualTo("chain");
        assertThat(out.attempts()).isEqualTo(1);
    }

    @Test
    void aJobRequestDefaultsToAVerifiedJtagLoad() {
        JobIn asked = read(Map.of("board", "bx-14", "digest", "ab".repeat(32), "requested_by", "team-a"), JobIn.class);
        assertThat(asked.mode()).isEqualTo("jtag");
        assertThat(asked.verify()).isTrue();
    }

    @Test
    void theClockIsADependencyATestCanReplace() {
        assertThat(bench.http().get("/when").json().get("now").asText()).isEqualTo(Times.iso(WHEN));
    }

    @Test
    void aUnitOfWorkIsHandedToARouteAndCommitted() {
        Http client = bench.http();
        assertThat(asMap(client.postJson("/work/boards", Map.of()))).isEqualTo(Map.of("added", true));
        assertThat(asMap(client.get("/work/boards"))).isEqualTo(Map.of("boards", List.of("bx-14")));
    }

    @Test
    void theSettingsDependencyHandsBackWhatTheAppHolds() {
        assertThat(asMap(bench.http().get("/site"))).isEqualTo(Map.of("site", "lab-c"));
    }

    /** Each shape's required fields, and one field of it that has a default. */
    static Stream<Arguments> defaultedFields() {
        Map<String, Object> board = Map.of(
                "asset", "bx-14",
                "model", "rx-carrier-b",
                "part", "XC7K325T-2FFG900I",
                "flash", "n25q128a13",
                "location", "rack-2/shelf-04/slot-3");
        Map<String, Object> state = Map.of("state", "maintenance");
        Map<String, Object> lease = Map.of("holder", "team-a", "purpose", "capture");
        Map<String, Object> job = Map.of("board", "bx-14", "digest", "ab12", "requested_by", "team-a");
        Map<String, Object> sample = Map.of("board", "bx-14", "millidegrees", 41_000);
        Map<String, Object> request = Map.of("holder", "team-a", "purpose", "capture");
        return Stream.of(
                Arguments.of(BoardIn.class, board, "revision"),
                Arguments.of(BoardIn.class, board, "slots"),
                Arguments.of(BoardIn.class, board, "vadj_millivolts"),
                Arguments.of(BoardIn.class, board, "header_voltage_mv"),
                Arguments.of(BoardIn.class, board, "max_tck_hz"),
                Arguments.of(BoardIn.class, board, "notes"),
                Arguments.of(StateIn.class, state, "reason"),
                Arguments.of(StateIn.class, state, "actor"),
                Arguments.of(LeaseIn.class, lease, "priority"),
                Arguments.of(LeaseIn.class, lease, "preemptible"),
                Arguments.of(JobIn.class, job, "mode"),
                Arguments.of(JobIn.class, job, "verify"),
                Arguments.of(SampleIn.class, sample, "source"),
                Arguments.of(RequestIn.class, request, "needs"),
                Arguments.of(RequestIn.class, request, "priority"),
                Arguments.of(RequestIn.class, request, "minutes"),
                Arguments.of(RequestIn.class, request, "preemptible"),
                Arguments.of(RequestIn.class, request, "needs_hands"));
    }

    @ParameterizedTest
    @MethodSource("defaultedFields")
    void aFieldWithADefaultTakesItWhenLeftOutButRefusesNull(
            Class<?> shape, Map<String, Object> required, String field) {
        assertThat(read(required, shape)).isNotNull();
        Map<String, Object> sent = new HashMap<>(required);
        sent.put(field, null);
        assertThatThrownBy(() -> read(sent, shape))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\"" + field + "\"");
    }

    @Test
    void aNullSentForAFieldWithADefaultIsRefusedByName() {
        Map<String, Object> sent = new HashMap<>();
        sent.put("state", "maintenance");
        sent.put("reason", null);
        Http.Answer refused = bench.http().postJson("/boards/bx-14/state", sent);
        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.json().at("/error/details/problems/0/where").asText())
                .isEqualTo("reason");
        assertThat(refused.json().at("/error/details/problems/0/why").asText()).isEqualTo(Errors.NOT_NULL);
    }

    private static Map<String, Object> asMap(Http.Answer answer) {
        return Http.JSON.convertValue(answer.json(), new TypeReference<Map<String, Object>>() {});
    }
}
