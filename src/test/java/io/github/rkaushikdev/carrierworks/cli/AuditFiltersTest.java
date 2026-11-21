package io.github.rkaushikdev.carrierworks.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.carrierworks.AuditEntry;
import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.api.TestBench;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.persistence.AuditRepository;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import io.github.rkaushikdev.carrierworks.persistence.UnitOfWork;
import io.github.rkaushikdev.carrierworks.services.Audit;
import io.github.rkaushikdev.carrierworks.services.Inventory;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Narrowing the audit trail, through the service, the command line and the route, over one file
 * database the command line and the service both read.
 */
class AuditFiltersTest {

    @TempDir
    static Path tmp;

    private static String url;
    private static TestBench bench;
    private Shell shell;

    @BeforeAll
    static void start() {
        url = Shell.url(tmp);
        bench = TestBench.over(Shell.migrated(url), BenchSettings.defaults(), Times.now());
    }

    @AfterAll
    static void stop() {
        bench.close();
    }

    @BeforeEach
    void fresh() {
        bench.reset(Times.now());
        database().run(unit -> {
            Inventory.addBoard(unit, BoardSetup.kintex("bx-14").build(), ago(Duration.ofDays(2)));
            Inventory.addBoard(unit, BoardSetup.artix("bx-15").build(), ago(Duration.ofDays(2)));
            line(unit, ago(Duration.ofHours(30)), "sam", "lease", "bx-14");
            line(unit, ago(Duration.ofHours(3)), "nightly", "program", "bx-14");
            line(unit, ago(Duration.ofHours(1)), "nightly", "program", "bx-15");
        });
        shell = new Shell(url);
    }

    private static BenchDatabase database() {
        return bench.database();
    }

    private static Instant ago(Duration span) {
        return Times.now().minus(span);
    }

    private static void line(UnitOfWork unit, Instant at, String actor, String action, String subject) {
        unit.audit().add(new AuditEntry(at, actor, action, subject, "", ""));
    }

    private static List<AuditEntry> search(UnaryOperator<AuditRepository.Search> narrow) {
        return database().apply(unit -> Audit.search(unit, narrow.apply(AuditRepository.Search.everything())));
    }

    @Test
    void theWholeTrailComesBackNewestFirst() {
        assertThat(search(all -> all)).extracting(AuditEntry::subject).containsExactly("bx-15", "bx-14", "bx-14");
    }

    @Test
    void oneSubjectCanBePickedOut() {
        assertThat(search(all -> all.subject("bx-15")))
                .extracting(AuditEntry::subject)
                .containsOnly("bx-15");
    }

    @Test
    void oneActorCanBePickedOut() {
        assertThat(search(all -> all.actor("nightly")))
                .extracting(AuditEntry::actor)
                .containsOnly("nightly");
    }

    @Test
    void oneActionCanBePickedOut() {
        assertThat(search(all -> all.action("lease"))).hasSize(1);
    }

    @Test
    void theFiltersCombine() {
        List<AuditEntry> found = search(all -> all.actor("nightly").subject("bx-14"));
        assertThat(found).hasSize(1);
        assertThat(found.get(0).subject()).isEqualTo("bx-14");
    }

    @Test
    void aDateLeavesOutWhatIsOlder() {
        assertThat(search(all -> all.since(ago(Duration.ofHours(6))))).hasSize(2);
    }

    @Test
    void theLimitHolds() {
        assertThat(search(all -> all.limit(1))).hasSize(1);
    }

    @Test
    void aFilterMatchingNothingIsEmpty() {
        assertThat(search(all -> all.actor("nobody"))).isEmpty();
    }

    @Test
    void theCommandPrintsTheTrail() {
        JsonNode body = shell.run("--json", "audit").json();
        assertThat(Shell.column(body, "subject")).containsExactly("bx-15", "bx-14", "bx-14");
    }

    @Test
    void theCommandFiltersByActor() {
        JsonNode body = shell.run("--json", "audit", "--actor", "sam").json();
        assertThat(Shell.column(body, "actor")).containsExactly("sam");
    }

    @Test
    void theCommandFiltersByHours() {
        assertThat(shell.run("--json", "audit", "--hours", "6").json()).hasSize(2);
    }

    @Test
    void theCommandRefusesASillyLimit() {
        Shell.Result result = shell.run("audit", "--limit", "0");
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("at least one line");
    }

    @Test
    void theRouteStillAnswersWithoutFilters() {
        assertThat(bench.http().get("/audit").json()).hasSize(3);
    }

    @Test
    void theRouteFiltersByActor() {
        JsonNode body = bench.http().get("/audit?actor=nightly").json();
        assertThat(Shell.column(body, "actor")).containsOnly("nightly");
    }

    @Test
    void theRouteFiltersByActionAndSubject() {
        JsonNode body = bench.http().get("/audit?action=program&subject=bx-15").json();
        assertThat(body).hasSize(1);
        assertThat(body.get(0).get("subject").asText()).isEqualTo("bx-15");
    }

    @Test
    void theRouteFiltersByDate() {
        String since = URLEncoder.encode(Times.iso(ago(Duration.ofHours(6))), StandardCharsets.UTF_8);
        assertThat(bench.http().get("/audit?since=" + since).json()).hasSize(2);
    }

    @Test
    void theRouteRefusesASillyLimit() {
        assertThat(bench.http().get("/audit?limit=0").status()).isEqualTo(422);
    }
}
