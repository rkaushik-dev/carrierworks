package io.github.rkaushikdev.carrierworks.jobs;

import static io.github.rkaushikdev.carrierworks.Measure.kib;
import static io.github.rkaushikdev.carrierworks.Measure.mib;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.catalog.FlashParts;
import io.github.rkaushikdev.carrierworks.catalog.FlashSpec;
import io.github.rkaushikdev.carrierworks.flash.EraseUnit;
import io.github.rkaushikdev.carrierworks.flash.FlashLayout;
import io.github.rkaushikdev.carrierworks.flash.FlashPlan;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FlasherTest {

    /** Every byte value in order, twenty times over. */
    private static final byte[] IMAGE = counting(20);

    private static byte[] counting(int repeats) {
        byte[] data = new byte[256 * repeats];
        for (int index = 0; index < data.length; index++) {
            data[index] = (byte) index;
        }
        return data;
    }

    private static byte[] filled(long length, int value) {
        byte[] data = new byte[Math.toIntExact(length)];
        Arrays.fill(data, (byte) value);
        return data;
    }

    private static byte[] reversed(byte[] data) {
        byte[] out = new byte[data.length];
        for (int index = 0; index < data.length; index++) {
            out[index] = data[data.length - 1 - index];
        }
        return out;
    }

    private static FlashSpec part() {
        return FlashParts.part("n25q128a13");
    }

    private static FlashLayout layout() {
        return FlashLayout.multiboot(part(), mib(4), mib(4), 0x800000);
    }

    private static FakeFlash driver() {
        return new FakeFlash(part());
    }

    @Test
    void theFakeSatisfiesTheDriverProtocol() {
        assertThat(driver()).isInstanceOf(SpiFlashDriver.class);
    }

    @Test
    void aFreshPartReadsAsErased() {
        assertThat(driver().read(0, 16)).containsExactly(filled(16, 0xFF));
    }

    @Test
    void thePartAnswersWithItsOwnId() {
        assertThat(driver().readId()).isEqualTo(0x20BA18);
    }

    @Test
    void anImageWritesAndVerifies() {
        FakeFlash flash = driver();
        FlashPlan plan = FlashPlan.forWrite(layout(), "update", IMAGE);
        FlashOutcome outcome = Flasher.writeImage(flash, plan, IMAGE, 0x800000);
        assertThat(outcome.verified()).isTrue();
        assertThat(outcome.bytesWritten()).isEqualTo(IMAGE.length);
        assertThat(flash.read(0x800000, IMAGE.length)).containsExactly(IMAGE);
    }

    @Test
    void theErasesAndPagesAreCounted() {
        FakeFlash flash = driver();
        FlashPlan plan = FlashPlan.forWrite(layout(), "update", filled(kib(64), 0x01));
        FlashOutcome outcome = Flasher.writeImage(flash, plan, filled(kib(64), 0x01), 0x800000);
        assertThat(outcome.erases()).isEqualTo(1);
        assertThat(outcome.pages()).isEqualTo(256);
        assertThat(flash.erases()).isEqualTo(1);
        assertThat(flash.writes()).isEqualTo(256);
    }

    @Test
    void nothingOutsideTheRegionIsTouched() {
        FakeFlash flash = driver();
        flash.preload(0, filled(16, 0xA5));
        FlashPlan plan = FlashPlan.forWrite(layout(), "update", IMAGE);
        Flasher.writeImage(flash, plan, IMAGE, 0x800000);
        assertThat(flash.read(0, 16)).containsExactly(filled(16, 0xA5));
    }

    @Test
    void programmingWithoutAnEraseProducesNeitherImage() {
        FakeFlash flash = driver();
        flash.program(0, new byte[] {0x0F});
        flash.program(0, new byte[] {(byte) 0xF0});
        assertThat(flash.read(0, 1)).containsExactly(0x00);
    }

    @Test
    void anErasePutsTheBitsBack() {
        FakeFlash flash = driver();
        flash.program(0, new byte[] {0x00});
        flash.erase(0, 4096);
        assertThat(flash.read(0, 1)).containsExactly(0xFF);
    }

    @Test
    void aSecondWriteOverTheSameRegionStillVerifies() {
        FakeFlash flash = driver();
        FlashPlan first = FlashPlan.forWrite(layout(), "update", IMAGE);
        Flasher.writeImage(flash, first, IMAGE, 0x800000);
        byte[] other = reversed(IMAGE);
        FlashPlan second = FlashPlan.forWrite(layout(), "update", other);
        Flasher.writeImage(flash, second, other, 0x800000);
        assertThat(flash.read(0x800000, other.length)).containsExactly(other);
    }

    @Test
    void aPartThatDoesNotHoldWhatItWasGivenFailsVerification() {
        FakeFlash flash = driver();
        flash.setWeakBytes(Set.of(0x800010L));
        FlashPlan plan = FlashPlan.forWrite(layout(), "update", IMAGE);
        VerifyFailed caught =
                catchThrowableOfType(VerifyFailed.class, () -> Flasher.writeImage(flash, plan, IMAGE, 0x800000));
        assertThat(caught.details()).containsEntry("region", "update");
        assertThat(caught.retryable()).isFalse();
    }

    @Test
    void verificationCanBeLeftOutAndThenNothingIsChecked() {
        FakeFlash flash = driver();
        flash.setWeakBytes(Set.of(0x800010L));
        FlashPlan plan = FlashPlan.forWrite(layout(), "update", IMAGE, EraseUnit.SECTOR, false);
        FlashOutcome outcome = Flasher.writeImage(flash, plan, IMAGE, 0x800000);
        assertThat(outcome.verified()).isFalse();
    }

    @Test
    void aPlanForAnotherImageIsRefused() {
        FakeFlash flash = driver();
        FlashPlan plan = FlashPlan.forWrite(layout(), "update", IMAGE);
        byte[] longer = Arrays.copyOf(IMAGE, IMAGE.length + 1);
        ValidationError caught =
                catchThrowableOfType(ValidationError.class, () -> Flasher.writeImage(flash, plan, longer, 0x800000));
        assertThat(caught.details()).containsEntry("planned", (long) IMAGE.length);
    }

    @Test
    void aPlanForAnotherPartIsRefused() {
        FakeFlash flash = new FakeFlash(FlashParts.part("w25q64jv"));
        FlashPlan plan = FlashPlan.forWrite(layout(), "update", filled(kib(4), 0x01));
        ValidationError caught = catchThrowableOfType(
                ValidationError.class, () -> Flasher.writeImage(flash, plan, filled(kib(4), 0x01), 0x800000));
        assertThat(caught.details()).containsEntry("expected", "0x20ba18");
    }

    @Test
    void aPageWriteCannotCrossAPageBoundary() {
        FakeFlash flash = driver();
        assertThatThrownBy(() -> flash.program(0xF0, filled(300, 0x01))).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> flash.program(0xF0, filled(200, 0x01))).isInstanceOf(ValidationError.class);
    }

    @Test
    void anEraseStartsOnASubsector() {
        FakeFlash flash = driver();
        assertThatThrownBy(() -> flash.erase(0x100, 4096)).isInstanceOf(ValidationError.class);
    }

    @Test
    void anAddressPastTheEndIsRefused() {
        FakeFlash flash = driver();
        assertThatThrownBy(() -> flash.read(mib(16), 4)).isInstanceOf(ValidationError.class);
    }

    @Test
    void theOutcomeDescribesItself() {
        FlashOutcome outcome = new FlashOutcome("update", 4096, 1, 16, true);
        assertThat(outcome.describe()).isEqualTo("update: 4096 bytes in 16 pages, 1 erases, verified");
    }

    @Test
    void theGoldenRegionWritesAtAddressZero() {
        FakeFlash flash = driver();
        FlashPlan plan = FlashPlan.forWrite(layout(), "golden", IMAGE);
        Flasher.writeImage(flash, plan, IMAGE, 0);
        assertThat(flash.read(0, IMAGE.length)).containsExactly(IMAGE);
    }
}
