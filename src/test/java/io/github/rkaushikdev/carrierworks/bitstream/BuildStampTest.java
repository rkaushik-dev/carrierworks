package io.github.rkaushikdev.carrierworks.bitstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.ValidationError;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class BuildStampTest {

    private static final LocalDateTime NIGHTLY = LocalDateTime.of(2026, 1, 15, 2, 0, 0);

    @Test
    void theFieldsArePackedDayFirst() {
        // 15 January 2026, 02:00:00: day 15, month 1, year 26, hour 2
        long word = (15L << 27) | (1L << 23) | (26L << 17) | (2L << 12);
        assertThat(new BuildStamp(NIGHTLY).encode()).isEqualTo(word);
        assertThat(BuildStamp.decode(word)).contains(new BuildStamp(NIGHTLY));
    }

    @Test
    void aStampRoundTripsToTheSecond() {
        LocalDateTime late = LocalDateTime.of(2063, 12, 31, 23, 59, 59);
        assertThat(BuildStamp.decode(new BuildStamp(late).encode()))
                .map(BuildStamp::at)
                .contains(late);
    }

    @Test
    void aConstantThatIsNotADateIsNoStamp() {
        assertThat(BuildStamp.decode(0xFFFF_FFFFL)).isEmpty();
        assertThat(BuildStamp.decode(0)).isEmpty();
    }

    @Test
    void aYearTheFieldCannotHoldIsRefused() {
        assertThatThrownBy(() -> new BuildStamp(LocalDateTime.of(1999, 1, 1, 0, 0)).encode())
                .isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> new BuildStamp(LocalDateTime.of(2064, 1, 1, 0, 0)).encode())
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void theImageCarriesItsStampInItsOwnStream() {
        Bitstream image = Images.image().stamp(NIGHTLY).bitstream();
        assertThat(image.stamp()).map(BuildStamp::describe).contains("2026-01-15 02:00:00");
        assertThat(BuildStamp.of(image.header().data())).isEqualTo(image.stamp());
    }

    @Test
    void theLastWriteWinsAndAnUnstampedImageSaysSo() {
        Bitstream twice =
                Images.image().stamp(NIGHTLY).stamp(NIGHTLY.plusDays(1)).bitstream();
        assertThat(twice.stamp()).map(BuildStamp::at).contains(NIGHTLY.plusDays(1));
        assertThat(Images.bitstream().stamp()).isEmpty();
        assertThat(Images.image().write(13, 0xDEAD_BEEFL).bitstream().stamp()).isEmpty();
    }

    @Test
    void theStampChangesTheDigestBecauseItIsPartOfTheImage() {
        assertThat(Images.image().stamp(NIGHTLY).bitstream().digest())
                .isNotEqualTo(
                        Images.image().stamp(NIGHTLY.plusSeconds(1)).bitstream().digest());
    }
}
