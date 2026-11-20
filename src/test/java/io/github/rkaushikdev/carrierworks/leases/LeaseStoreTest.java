package io.github.rkaushikdev.carrierworks.leases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.carrierworks.Conflict;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LeaseStoreTest {

    private static final Instant NOW = Instant.parse("2025-08-04T09:00:00Z");

    private static LeaseStore store() {
        return new LeaseStore();
    }

    private static Instant at(Duration offset) {
        return NOW.plus(offset);
    }

    @Test
    void takingABoardGivesALease() {
        LeaseStore live = store();
        Lease lease = live.acquire("bx-14", "signal-processing", "capture regression", NOW);
        assertThat(lease.id()).isEqualTo("lease-00001");
        assertThat(lease.board()).isEqualTo("bx-14");
        assertThat(lease.expiresAt()).isEqualTo(at(Duration.ofHours(1)));
        assertThat(live.forBoard("bx-14", NOW)).containsSame(lease);
    }

    @Test
    void idsRunInOrder() {
        LeaseStore live = store();
        live.acquire("bx-14", "team", "one", NOW);
        Lease second = live.acquire("bx-15", "team", "two", NOW);
        assertThat(second.id()).isEqualTo("lease-00002");
        assertThat(live.size()).isEqualTo(2);
    }

    @Test
    void aBoardCannotBeLeasedTwice() {
        LeaseStore live = store();
        live.acquire("bx-14", "signal-processing", "capture", NOW);
        Conflict caught = catchThrowableOfType(
                Conflict.class, () -> live.acquire("bx-14", "firmware", "bring up", at(Duration.ofMinutes(5))));
        assertThat(caught.details()).containsEntry("holder", "signal-processing");
        assertThat(caught.code()).isEqualTo("conflict");
    }

    @Test
    void aBoardFreesUpWhenItsLeaseIsReleased() {
        LeaseStore live = store();
        Lease first = live.acquire("bx-14", "team-a", "capture", NOW);
        live.release(first.id(), at(Duration.ofMinutes(10)), "finished");
        Lease second = live.acquire("bx-14", "team-b", "bring up", at(Duration.ofMinutes(11)));
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(live.forBoard("bx-14", at(Duration.ofMinutes(11)))).containsSame(second);
    }

    @Test
    void aBoardFreesUpWhenItsLeaseRunsOut() {
        LeaseStore live = store();
        live.acquire("bx-14", "team-a", "capture", NOW, 30);
        Instant later = at(Duration.ofMinutes(31));
        assertThat(live.forBoard("bx-14", later)).isEmpty();
        assertThat(live.acquire("bx-14", "team-b", "bring up", later).holder()).isEqualTo("team-b");
    }

    @Test
    void anExpiredLeaseIsWrittenDownAsExpiredByTheSweep() {
        LeaseStore live = store();
        live.acquire("bx-14", "team-a", "capture", NOW, 30);
        List<Lease> closed = live.sweep(at(Duration.ofHours(2)));
        assertThat(closed).extracting(Lease::releaseReason).containsExactly("expired");
        assertThat(closed.getFirst().releasedAt()).contains(at(Duration.ofMinutes(30)));
        assertThat(live.sweep(at(Duration.ofHours(3)))).isEmpty();
    }

    @Test
    void theSweepLeavesLiveLeasesAlone() {
        LeaseStore live = store();
        live.acquire("bx-14", "team-a", "capture", NOW, 120);
        assertThat(live.sweep(at(Duration.ofMinutes(30)))).isEmpty();
    }

    @Test
    void renewingExtendsALease() {
        LeaseStore live = store();
        Lease lease = live.acquire("bx-14", "team-a", "capture", NOW, 30);
        Lease renewed = live.renew(lease.id(), at(Duration.ofMinutes(25)), 60);
        assertThat(renewed.expiresAt()).isEqualTo(at(Duration.ofMinutes(85)));
        assertThat(live.forBoard("bx-14", at(Duration.ofMinutes(60)))).isPresent();
    }

    @Test
    void renewalCannotGoPastTheStoreCeiling() {
        LeaseStore live = new LeaseStore(60);
        Lease lease = live.acquire("bx-14", "team-a", "capture", NOW, 30);
        Lease renewed = live.renew(lease.id(), at(Duration.ofMinutes(20)), 600);
        assertThat(renewed.expiresAt()).isEqualTo(at(Duration.ofMinutes(60)));
    }

    @Test
    void aLeaseLongerThanTheCeilingIsRefusedOutright() {
        LeaseStore live = new LeaseStore(60);
        ValidationError caught =
                catchThrowableOfType(ValidationError.class, () -> live.acquire("bx-14", "team-a", "capture", NOW, 120));
        assertThat(caught.details()).containsEntry("ceiling", 60);
    }

    @Test
    void aLeaseOfNoMinutesIsRefused() {
        assertThatThrownBy(() -> store().acquire("bx-14", "team-a", "capture", NOW, 0))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aStoreWithNoCeilingIsRefused() {
        assertThatThrownBy(() -> new LeaseStore(0)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aLeaseNobodyIssuedIsANotFound() {
        assertThatThrownBy(() -> store().lease("lease-09999")).isInstanceOf(NotFound.class);
    }

    @Test
    void whatAHolderIsSittingOn() {
        LeaseStore live = store();
        live.acquire("bx-14", "signal-processing", "capture", NOW);
        live.acquire("bx-15", "signal-processing", "capture", NOW);
        live.acquire("bx-16", "firmware", "bring up", NOW);
        assertThat(live.heldBy("Signal Processing", NOW))
                .extracting(Lease::board)
                .containsExactly("bx-14", "bx-15");
        assertThat(live.countsByHolder(NOW))
                .containsExactly(Map.entry("signal-processing", 2), Map.entry("firmware", 1));
    }

    @Test
    void holdingsDropOffWhenLeasesEnd() {
        LeaseStore live = store();
        Lease lease = live.acquire("bx-14", "team-a", "capture", NOW, 15);
        assertThat(live.countsByHolder(at(Duration.ofMinutes(20)))).isEmpty();
        assertThat(live.heldBy("team-a", at(Duration.ofMinutes(20)))).isEmpty();
        assertThat(live.lease(lease.id()).released()).isFalse();
    }

    @Test
    void activeLeasesAndBoardsHeld() {
        LeaseStore live = store();
        live.acquire("bx-15", "team-a", "capture", NOW);
        live.acquire("bx-14", "team-b", "capture", at(Duration.ofMinutes(1)));
        assertThat(live.boardsHeld(at(Duration.ofMinutes(2)))).containsExactly("bx-14", "bx-15");
        assertThat(live.active(at(Duration.ofMinutes(2))))
                .extracting(Lease::board)
                .containsExactly("bx-15", "bx-14");
    }

    @Test
    void theHistoryOfABoardKeepsTheClosedLeases() {
        LeaseStore live = store();
        Lease first = live.acquire("bx-14", "team-a", "capture", NOW, 10);
        live.release(first.id(), at(Duration.ofMinutes(5)));
        live.acquire("bx-14", "team-b", "bring up", at(Duration.ofMinutes(6)));
        assertThat(live.history("bx-14")).extracting(Lease::holder).containsExactly("team-a", "team-b");
        assertThat(live.all()).hasSize(2);
    }

    @Test
    void releasingALeaseTwiceIsRefused() {
        LeaseStore live = store();
        Lease lease = live.acquire("bx-14", "team-a", "capture", NOW);
        live.release(lease.id(), at(Duration.ofMinutes(5)));
        assertThatThrownBy(() -> live.release(lease.id(), at(Duration.ofMinutes(6))))
                .isInstanceOf(ValidationError.class);
    }
}
