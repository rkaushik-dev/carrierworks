package io.github.rkaushikdev.carrierworks.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.withinPercentage;

import io.github.rkaushikdev.carrierworks.RequirementSet;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MetricsTest {

    private static final Instant START = Instant.parse("2025-08-25T00:00:00Z");
    private static final Instant END = START.plus(Duration.ofHours(24));

    /** A lease of whole hours on one board, released when it ran out unless {@code released} is false. */
    private static Lease lease(String board, String holder, int startHour, int hours, boolean released) {
        Instant taken = START.plus(Duration.ofHours(startHour));
        Instant expires = taken.plus(Duration.ofHours(hours));
        Lease live = new Lease("lease-" + board + "-" + startHour, board, holder, "capture", taken, expires);
        return released ? live.release(expires, "finished") : live;
    }

    private static Lease lease(String board, String holder, int startHour, int hours) {
        return lease(board, holder, startHour, hours, true);
    }

    private static Lease lease(int startHour, int hours) {
        return lease("bx-14", "team-a", startHour, hours);
    }

    @Test
    void busyTimeAddsUpPerBoard() {
        List<Lease> leases = List.of(lease(0, 2), lease(6, 3), lease("bx-15", "team-a", 0, 1));
        Map<String, Double> busy = Metrics.busySeconds(leases, START, END);
        assertThat(busy).containsEntry("bx-14", 5 * 3600.0);
        assertThat(busy).containsEntry("bx-15", 3600.0);
    }

    @Test
    void aLeaseThatStartedBeforeTheWindowIsClipped() {
        Lease early = new Lease(
                "lease-1",
                "bx-14",
                "team-a",
                "capture",
                START.minus(Duration.ofHours(4)),
                START.plus(Duration.ofHours(2)));
        assertThat(Metrics.busySeconds(List.of(early), START, END)).containsEntry("bx-14", 2 * 3600.0);
    }

    @Test
    void aLeaseThatRunsPastTheWindowIsClippedToo() {
        Lease late = lease("bx-14", "team-a", 23, 5, false);
        assertThat(Metrics.busySeconds(List.of(late), START, END)).containsEntry("bx-14", 3600.0);
    }

    @Test
    void aLeaseWhollyOutsideTheWindowCountsForNothing() {
        Lease outside = lease(30, 1);
        assertThat(Metrics.busySeconds(List.of(outside), START, END)).isEmpty();
    }

    @Test
    void utilisationIncludesTheBoardsNobodyUsed() {
        List<Lease> leases = List.of(lease(0, 6));
        Map<String, Double> numbers = Metrics.utilisation(leases, List.of("bx-14", "bx-15"), START, END);
        assertThat(numbers.get("bx-14")).isCloseTo(0.25, withinPercentage(1e-4));
        assertThat(numbers).containsEntry("bx-15", 0.0);
    }

    @Test
    void utilisationNeverGoesOverOne() {
        List<Lease> leases = List.of(lease(0, 12), lease(6, 12));
        assertThat(Metrics.utilisation(leases, List.of("bx-14"), START, END)).containsEntry("bx-14", 1.0);
    }

    @Test
    void aWindowThatEndsBeforeItStartsIsABug() {
        assertThatThrownBy(() -> Metrics.utilisation(List.of(), List.of("bx-14"), END, START))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void boardHoursByHolder() {
        List<Lease> leases = List.of(
                lease("bx-14", "team-a", 0, 2), lease("bx-15", "team-a", 0, 1), lease("bx-14", "nightly", 4, 3));
        assertThat(Metrics.holderHours(leases, START, END)).isEqualTo(Map.of("team-a", 3.0, "nightly", 3.0));
    }

    @Test
    void holderHoursAreRoundedForReading() {
        Lease odd = lease(0, 1);
        Map<String, Double> hours = Metrics.holderHours(List.of(odd), START, START.plus(Duration.ofHours(2)));
        assertThat(hours).isEqualTo(Map.of("team-a", 1.0));
    }

    @Test
    void theIdleBoardsAreTheOnesNobodyTouched() {
        List<Lease> leases = List.of(lease(0, 1));
        assertThat(Metrics.idleBoards(leases, List.of("bx-14", "bx-15", "bx-16"), START, END))
                .containsExactly("bx-15", "bx-16");
    }

    @Test
    void anEmptyQueueHasNoWaits() {
        WaitStats stats = Metrics.waitStats(new RequestQueue(), START);
        assertThat(stats.isEmpty()).isTrue();
        assertThat(stats.describe()).isEqualTo("nothing waiting");
    }

    @Test
    void waitStatsOverAnOddNumberOfRequests() {
        RequestQueue queue = new RequestQueue();
        RequirementSet wanted = RequirementSet.parse(List.of());
        queue.submit("team-a", "one", wanted, START);
        queue.submit("team-b", "two", wanted, START.plus(Duration.ofMinutes(10)));
        queue.submit("team-c", "three", wanted, START.plus(Duration.ofMinutes(30)));
        WaitStats stats = Metrics.waitStats(queue, START.plus(Duration.ofMinutes(40)));
        assertThat(stats.waiting()).isEqualTo(3);
        assertThat(stats.longest()).isEqualTo(Duration.ofMinutes(40));
        assertThat(stats.median()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void waitStatsOverAnEvenNumberTakesTheMiddlePair() {
        RequestQueue queue = new RequestQueue();
        RequirementSet wanted = RequirementSet.parse(List.of());
        queue.submit("team-a", "one", wanted, START);
        queue.submit("team-b", "two", wanted, START.plus(Duration.ofMinutes(20)));
        WaitStats stats = Metrics.waitStats(queue, START.plus(Duration.ofMinutes(40)));
        assertThat(stats.median()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void satisfiedRequestsLeaveTheWaitStats() {
        RequestQueue queue = new RequestQueue();
        RequirementSet wanted = RequirementSet.parse(List.of());
        Request request = queue.submit("team-a", "one", wanted, START);
        queue.satisfy(request.id(), START.plus(Duration.ofMinutes(5)));
        assertThat(Metrics.waitStats(queue, START.plus(Duration.ofMinutes(40))).isEmpty())
                .isTrue();
    }

    @Test
    void waitStatsDescribeThemselvesInMinutes() {
        RequestQueue queue = new RequestQueue();
        queue.submit("team-a", "one", RequirementSet.parse(List.of()), START);
        String described =
                Metrics.waitStats(queue, START.plus(Duration.ofMinutes(12))).describe();
        assertThat(described).isEqualTo("1 waiting, longest 12m, median 12m");
    }
}
