package io.github.rkaushikdev.carrierworks.flash;

import static io.github.rkaushikdev.carrierworks.Measure.kib;
import static io.github.rkaushikdev.carrierworks.Measure.mib;
import static io.github.rkaushikdev.carrierworks.flash.Multiboot.assemble;
import static io.github.rkaushikdev.carrierworks.flash.Multiboot.goldenWithJump;
import static io.github.rkaushikdev.carrierworks.flash.Multiboot.readWarmBootAddress;
import static io.github.rkaushikdev.carrierworks.flash.Multiboot.warmBootPrefix;
import static io.github.rkaushikdev.carrierworks.flash.RegionPurpose.GOLDEN;
import static io.github.rkaushikdev.carrierworks.flash.RegionPurpose.UPDATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.bitstream.ConfigPacket;
import io.github.rkaushikdev.carrierworks.bitstream.Packets;
import io.github.rkaushikdev.carrierworks.catalog.FlashParts;
import io.github.rkaushikdev.carrierworks.catalog.FlashSpec;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class MultibootTest {

    private static FlashSpec part() {
        return FlashParts.part("n25q128a13");
    }

    private static FlashLayout layout() {
        return FlashLayout.multiboot(part(), mib(4), mib(4), 0x800000);
    }

    private static byte[] filled(long length, int value) {
        byte[] data = new byte[Math.toIntExact(length)];
        Arrays.fill(data, (byte) value);
        return data;
    }

    private static byte[] slice(byte[] data, long from, long to) {
        return Arrays.copyOfRange(data, Math.toIntExact(from), Math.toIntExact(to));
    }

    @Test
    void thePrefixIsTheSequenceFromTheUserGuide() {
        byte[] prefix = warmBootPrefix(0x800000);
        List<Long> words = IntStream.range(0, 8)
                .mapToObj(index -> Packets.wordAt(prefix, index * 4))
                .toList();
        assertThat(words)
                .containsExactly(
                        0xFFFFFFFFL,
                        Packets.SYNC_WORD,
                        0x20000000L,
                        0x30020001L,
                        0x00800000L,
                        0x30008001L,
                        0x0000000FL,
                        0x20000000L);
    }

    @Test
    void thePrefixParsesAsPacketsThatSayWhatItDoes() {
        List<ConfigPacket> packets = Packets.parse(warmBootPrefix(0x800000));
        assertThat(packets.stream().filter(ConfigPacket::isWrite).map(ConfigPacket::registerName))
                .containsExactly("wbstar", "cmd");
        assertThat(Packets.commandNames(packets)).containsExactly("iprog");
    }

    @Test
    void theAddressReadsBackOutOfThePrefix() {
        assertThat(readWarmBootAddress(warmBootPrefix(0x800000))).contains(0x800000L);
        assertThat(readWarmBootAddress(warmBootPrefix(0))).contains(0L);
    }

    @Test
    void anOrdinaryImageIsNotMistakenForAJump() {
        assertThat(readWarmBootAddress(new byte[64])).isEmpty();
        assertThat(readWarmBootAddress(new byte[0])).isEmpty();
    }

    @Test
    void aNearlyRightSequenceIsStillNotAJump() {
        byte[] broken = warmBootPrefix(0x800000);
        System.arraycopy(Packets.wordsToBytes(0x0000000DL), 0, broken, 24, 4);
        assertThat(readWarmBootAddress(broken)).isEmpty();
    }

    @Test
    void anAddressWiderThanTheRegisterIsRefused() {
        assertThatThrownBy(() -> warmBootPrefix(0x20000000)).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> warmBootPrefix(-1)).isInstanceOf(ValidationError.class);
    }

    @Test
    void theAssembledImageStartsWithTheJump() {
        byte[] whole = assemble(layout(), filled(kib(64), 0xA1), filled(kib(64), 0xB2));
        assertThat(readWarmBootAddress(whole)).contains(0x800000L);
    }

    @Test
    void theGoldenImageFollowsTheJumpImmediately() {
        byte[] golden = filled(kib(64), 0xA1);
        byte[] whole = assemble(layout(), golden, filled(kib(4), 0xB2));
        assertThat(slice(whole, 32, 32 + golden.length)).isEqualTo(golden);
    }

    @Test
    void theUpdateImageLandsAtItsAddress() {
        byte[] update = filled(kib(64), 0xB2);
        byte[] whole = assemble(layout(), filled(kib(64), 0xA1), update);
        assertThat(slice(whole, 0x800000, 0x800000 + update.length)).isEqualTo(update);
        assertThat(whole).hasSize(0x800000 + update.length);
    }

    @Test
    void theGapBetweenThemIsErasedRatherThanZeroed() {
        byte[] whole = assemble(layout(), filled(kib(4), 0xA1), filled(kib(4), 0xB2));
        assertThat(slice(whole, kib(4) + 32, kib(8))).isEqualTo(filled(kib(4) - 32, 0xFF));
    }

    @Test
    void theFillCanBeChangedForAPartThatErasesToZero() {
        byte[] whole = assemble(layout(), filled(kib(4), 0xA1), filled(kib(4), 0xB2), 0x00);
        assertThat(slice(whole, kib(4) + 32, kib(8))).isEqualTo(filled(kib(4) - 32, 0x00));
    }

    @Test
    void aGoldenImageThatDoesNotLeaveRoomForTheJumpIsRefused() {
        FlashLayout small = FlashLayout.multiboot(part(), kib(64), kib(64), 0x200000);
        ValidationError caught = catchThrowableOfType(
                ValidationError.class, () -> assemble(small, filled(kib(64), 0xA1), filled(kib(4), 0xB2)));
        assertThat(caught.details()).containsEntry("room", kib(64));
    }

    @Test
    void anUpdateImageThatDoesNotFitItsRegionIsRefused() {
        assertThatThrownBy(() -> assemble(layout(), filled(kib(4), 0xA1), filled(mib(5), 0xB2)))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aLayoutWhoseGoldenImageIsNotAtZeroIsRefused() {
        FlashLayout moved = new FlashLayout(
                part(),
                List.of(
                        new FlashRegion("golden", mib(1), mib(1), GOLDEN),
                        new FlashRegion("update", mib(4), mib(1), UPDATE)));
        assertThatThrownBy(() -> assemble(moved, filled(kib(4), 0xA1), filled(kib(4), 0xB2)))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aLayoutWithNoUpdateRegionCannotMultiboot() {
        FlashLayout single = FlashLayout.singleImage(part(), mib(1));
        assertThatThrownBy(() -> assemble(single, new byte[] {1}, new byte[] {2}))
                .isInstanceOf(NotFound.class);
    }

    @Test
    void theGoldenImageCanBeProgrammedOnItsOwnWithItsJump() {
        byte[] golden = filled(kib(4), 0xA1);
        byte[] head = goldenWithJump(layout(), golden);
        assertThat(readWarmBootAddress(head)).contains(0x800000L);
        assertThat(slice(head, 32, head.length)).isEqualTo(golden);
    }
}
