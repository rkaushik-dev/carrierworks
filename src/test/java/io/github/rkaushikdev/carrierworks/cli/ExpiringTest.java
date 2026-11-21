package io.github.rkaushikdev.carrierworks.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.carrierworks.AuditEntry;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import io.github.rkaushikdev.carrierworks.services.Inventory;
import io.github.rkaushikdev.carrierworks.services.Leasing;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExpiringTest {

    private static final Pattern MINUTES = Pattern.compile("(\\d+)\\s*m(?:inutes?)?\\b");

    @TempDir
    Path tmp;

    private BenchDatabase database;
    private Shell shell;

    @BeforeEach
    void database() {
        String url = Shell.url(tmp);
        database = Shell.migrated(url);
        database.run(unit -> {
            Inventory.addBoard(
                    unit, BoardSetup.kintex("bx-14").build(), Times.now().minus(Duration.ofDays(1)));
            Inventory.addBoard(
                    unit, BoardSetup.artix("bx-15").build(), Times.now().minus(Duration.ofDays(1)));
        });
        shell = new Shell(url);
    }

    @AfterEach
    void close() {
        database.close();
    }

    /**
     * The minutes a line reports, out of "9m" or "runs out in 9 minutes".
     *
     * <p>These cases run off the real clock rather than a frozen one, and the bench floors the
     * minutes it prints, so a lease with ten minutes left reads as nine the moment any time passes.
     * Both answers are right; asserting on one of them is asserting on how fast this machine is.
     */
    private static int minutesIn(String text) {
        Matcher found = MINUTES.matcher(text);
        assertThat(found.find()).as(text).isTrue();
        return Integer.parseInt(found.group(1));
    }

    private Lease hold(String board, String holder, int minutes, int ago) {
        return database.apply(unit ->
                Leasing.acquire(unit, board, holder, "capture", Times.now().minus(Duration.ofMinutes(ago)), minutes));
    }

    private Lease hold(int minutes, int ago) {
        return hold("bx-14", "team-a", minutes, ago);
    }

    private List<Lease> soon() {
        return database.apply(unit -> Leasing.expiring(unit, Times.now()));
    }

    private List<Lease> soon(Duration within) {
        return database.apply(unit -> Leasing.expiring(unit, Times.now(), within));
    }

    private static List<AuditEntry> byTheBench(List<AuditEntry> trail) {
        return trail.stream().filter(line -> line.actor().equals("bench")).toList();
    }

    @Test
    void aLeaseWithHoursLeftIsNotExpiring() {
        hold(300, 0);
        assertThat(soon()).isEmpty();
    }

    @Test
    void aLeaseAboutToRunOutIsListed() {
        Lease lease = hold(60, 50);
        assertThat(soon().stream().map(Lease::id)).containsExactly(lease.id());
    }

    @Test
    void theWindowCanBeWidened() {
        hold(60, 20);
        assertThat(soon()).isEmpty();
        assertThat(soon(Duration.ofMinutes(45))).hasSize(1);
    }

    @Test
    void theSoonestComesFirst() {
        Lease later = hold("bx-14", "team-a", 60, 45);
        Lease sooner = hold("bx-15", "team-a", 60, 55);
        List<Lease> found = soon(Duration.ofMinutes(20));
        assertThat(found.stream().map(Lease::id)).containsExactly(sooner.id(), later.id());
    }

    @Test
    void aReleasedLeaseIsNotExpiring() {
        Lease lease = hold(60, 50);
        database.run(unit -> Leasing.release(unit, lease.id(), Times.now(), "done"));
        assertThat(soon()).isEmpty();
    }

    @Test
    void aWindowWithNoLengthIsRefused() {
        assertThatThrownBy(() -> soon(Duration.ZERO)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aReminderWritesOneLinePerLease() {
        Lease lease = hold(60, 50);
        database.run(unit -> {
            List<Lease> warned = Leasing.remind(unit, Times.now());
            List<AuditEntry> trail = unit.audit().forSubject("bx-14");
            assertThat(warned.stream().map(Lease::id)).containsExactly(lease.id());
            assertThat(byTheBench(trail).stream().map(AuditEntry::action)).containsExactly("expiring");
        });
    }

    @Test
    void theReminderSaysWhoAndHowLong() {
        hold("bx-14", "team-b", 60, 50);
        String detail = database.apply(unit -> {
            Leasing.remind(unit, Times.now());
            return byTheBench(unit.audit().forSubject("bx-14")).getFirst().detail();
        });
        assertThat(detail).contains("team-b");
        assertThat(minutesIn(detail)).isIn(9, 10);
    }

    @Test
    void nothingExpiringWritesNothing() {
        hold(300, 0);
        database.run(unit -> {
            assertThat(Leasing.remind(unit, Times.now())).isEmpty();
            assertThat(byTheBench(unit.audit().forSubject("bx-14"))).isEmpty();
        });
    }

    @Test
    void theCommandListsWhatIsAboutToGo() {
        hold(60, 50);
        JsonNode body = shell.run("--json", "lease", "expiring").json();
        assertThat(body.get(0).get("asset").asText()).isEqualTo("bx-14");
        assertThat(minutesIn(body.get(0).get("left").asText())).isIn(9, 10);
    }

    @Test
    void theCommandWindowCanBeWidened() {
        hold(60, 20);
        JsonNode none = shell.run("--json", "lease", "expiring").json();
        assertThat(none.isArray()).isTrue();
        assertThat(none).isEmpty();
        assertThat(shell.run("--json", "lease", "expiring", "--minutes", "45").json())
                .hasSize(1);
    }

    @Test
    void theCommandCanWriteTheReminder() {
        hold(60, 50);
        shell.run("lease", "expiring", "--remind");
        JsonNode body = shell.run("--json", "audit", "--action", "expiring").json();
        assertThat(body).hasSize(1);
    }

    @Test
    void theCommandIsQuietWhenNothingIsExpiring() {
        hold(300, 0);
        assertThat(shell.run("lease", "expiring").output()).contains("nothing to show");
    }
}
