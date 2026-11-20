package io.github.rkaushikdev.carrierworks.leases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.carrierworks.ValidationError;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LeaseModelsTest {

    private static final Instant NOW = Instant.parse("2025-08-01T09:00:00Z");

    private static Lease.Builder fields() {
        return Lease.builder(
                "lease-00001",
                "BX-0014",
                "Signal Processing",
                "nightly capture regression",
                NOW,
                NOW.plus(Duration.ofHours(1)));
    }

    private static Lease lease() {
        return fields().build();
    }

    private static Instant at(Duration offset) {
        return NOW.plus(offset);
    }

    @Test
    void aLeaseNormalisesItsNames() {
        Lease live = lease();
        assertThat(live.board()).isEqualTo("bx-14");
        assertThat(live.holder()).isEqualTo("signal-processing");
        assertThat(live.priority()).isEqualTo(5);
    }

    @Test
    void aLeaseIsActiveUntilItExpires() {
        Lease live = lease();
        assertThat(live.active(NOW)).isTrue();
        assertThat(live.active(at(Duration.ofMinutes(59)))).isTrue();
        assertThat(live.active(at(Duration.ofHours(1)))).isFalse();
        assertThat(live.expired(at(Duration.ofHours(1)))).isTrue();
    }

    @Test
    void whatIsLeftRunsDownToNothing() {
        Lease live = lease();
        assertThat(live.remaining(NOW)).isEqualTo(Duration.ofHours(1));
        assertThat(live.remaining(at(Duration.ofMinutes(45)))).isEqualTo(Duration.ofMinutes(15));
        assertThat(live.remaining(at(Duration.ofHours(2)))).isEqualTo(Duration.ZERO);
    }

    @Test
    void howLongABoardWasHeldStopsAtTheExpiry() {
        Lease live = lease();
        assertThat(live.heldFor(at(Duration.ofMinutes(20)))).isEqualTo(Duration.ofMinutes(20));
        assertThat(live.heldFor(at(Duration.ofHours(5)))).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void releasingStopsTheClock() {
        Lease released = lease().release(at(Duration.ofMinutes(20)), "job finished");
        assertThat(released.released()).isTrue();
        assertThat(released.active(at(Duration.ofMinutes(21)))).isFalse();
        assertThat(released.heldFor(at(Duration.ofHours(3)))).isEqualTo(Duration.ofMinutes(20));
        assertThat(released.describe()).contains("released (job finished)");
    }

    @Test
    void aLeaseCannotBeReleasedTwice() {
        Lease released = lease().release(at(Duration.ofMinutes(5)));
        assertThatThrownBy(() -> released.release(at(Duration.ofMinutes(6)))).isInstanceOf(ValidationError.class);
    }

    @Test
    void renewingPushesTheExpiryOutFromNow() {
        Lease renewed = lease().renewed(at(Duration.ofMinutes(50)), 60);
        assertThat(renewed.expiresAt()).isEqualTo(at(Duration.ofMinutes(110)));
        assertThat(renewed.renewals()).isEqualTo(1);
    }

    @Test
    void renewalStopsAtTheCeilingMeasuredFromTheStart() {
        Lease renewed = lease().renewed(at(Duration.ofMinutes(50)), 600, 120);
        assertThat(renewed.expiresAt()).isEqualTo(at(Duration.ofMinutes(120)));
    }

    @Test
    void aLeasePastItsCeilingCannotBeRenewedAtAll() {
        ValidationError caught = catchThrowableOfType(
                ValidationError.class, () -> lease().renewed(at(Duration.ofMinutes(200)), 30, 120));
        assertThat(caught.details()).containsEntry("ceiling_minutes", 120);
    }

    @Test
    void aReleasedLeaseCannotBeRenewed() {
        Lease released = lease().release(at(Duration.ofMinutes(5)));
        assertThatThrownBy(() -> released.renewed(at(Duration.ofMinutes(6)), 30))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void renewingForNoTimeIsABug() {
        assertThatThrownBy(() -> lease().renewed(NOW, 0)).isInstanceOf(ValidationError.class);
    }

    @Test
    void expiringALeaseClosesItAtItsOwnExpiry() {
        Lease expired = lease().expire();
        assertThat(expired.releasedAt()).contains(at(Duration.ofHours(1)));
        assertThat(expired.releaseReason()).isEqualTo("expired");
    }

    @Test
    void expiringAReleasedLeaseChangesNothing() {
        Lease released = lease().release(at(Duration.ofMinutes(5)));
        assertThat(released.expire()).isEqualTo(released);
    }

    @Test
    void priorityDecidesWhoOutranksWhom() {
        Lease urgent = fields().priority(2).build();
        Lease ordinary = fields().priority(5).build();
        assertThat(urgent.outranks(ordinary)).isTrue();
        assertThat(ordinary.outranks(urgent)).isFalse();
        assertThat(ordinary.outranks(fields().priority(5).build())).isFalse();
    }

    @Test
    void preemptibilityIsNotPriority() {
        Lease stubborn = fields().priority(9).preemptible(false).build();
        assertThat(stubborn.preemptible()).isFalse();
        assertThat(fields().priority(1).build().preemptible()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 10, -1})
    void aPriorityOffTheScaleIsRefused(int priority) {
        assertThatThrownBy(() -> fields().priority(priority).build()).isInstanceOf(ValidationError.class);
    }

    @Test
    void aLeaseThatExpiresBeforeItStartsIsRefused() {
        assertThatThrownBy(() ->
                        fields().expiresAt(NOW.minus(Duration.ofMinutes(1))).build())
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aLeaseWithoutAPurposeIsRefused() {
        assertThatThrownBy(() -> fields().purpose("   ").build()).isInstanceOf(ValidationError.class);
    }

    /**
     * A lease time with no zone is refused. A lease keeps its moments as {@link Instant}s, which are
     * always UTC, so a zoneless time cannot be handed in; the test holds the types to that.
     */
    @Test
    void leaseTimesAreInUtc() {
        assertThat(Arrays.stream(Lease.class.getRecordComponents())
                        .filter(component -> List.of("takenAt", "expiresAt").contains(component.getName()))
                        .<Object>map(component -> component.getType()))
                .containsExactly(Instant.class, Instant.class);
    }

    @Test
    void aLiveLeaseDescribesWhenItRunsOut() {
        assertThat(lease().describe()).endsWith("until 2025-08-01T10:00+00:00");
    }
}
