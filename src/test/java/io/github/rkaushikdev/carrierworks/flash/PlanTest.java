package io.github.rkaushikdev.carrierworks.flash;

import static io.github.rkaushikdev.carrierworks.Measure.kib;
import static io.github.rkaushikdev.carrierworks.Measure.mib;
import static io.github.rkaushikdev.carrierworks.flash.EraseUnit.SECTOR;
import static io.github.rkaushikdev.carrierworks.flash.EraseUnit.SUBSECTOR;
import static io.github.rkaushikdev.carrierworks.flash.RegionPurpose.DATA;
import static io.github.rkaushikdev.carrierworks.flash.StepKind.ERASE;
import static io.github.rkaushikdev.carrierworks.flash.StepKind.PROGRAM;
import static io.github.rkaushikdev.carrierworks.flash.StepKind.VERIFY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.catalog.FlashParts;
import io.github.rkaushikdev.carrierworks.catalog.FlashSpec;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanTest {

    private static FlashSpec part() {
        return FlashParts.part("n25q128a13");
    }

    private static FlashLayout layout() {
        return FlashLayout.multiboot(part(), mib(4), mib(4), 0x800000);
    }

    private static FlashLayout params(long start, long length) {
        return new FlashLayout(part(), List.of(new FlashRegion("params", start, length, DATA)));
    }

    private static byte[] filled(long length, int value) {
        byte[] data = new byte[Math.toIntExact(length)];
        Arrays.fill(data, (byte) value);
        return data;
    }

    @Test
    void aWriteErasesProgramsAndVerifiesInThatOrder() {
        FlashPlan plan = FlashPlan.forWrite(layout(), "update", filled(kib(8), 0xA5));
        List<StepKind> kinds = plan.steps().stream().map(FlashStep::kind).toList();
        assertThat(kinds.getFirst()).isEqualTo(ERASE);
        assertThat(kinds).filteredOn(kind -> kind == ERASE).hasSize(1);
        assertThat(kinds).contains(PROGRAM);
        assertThat(kinds.getLast()).isEqualTo(VERIFY);
        assertThat(kinds.indexOf(PROGRAM)).isLessThan(kinds.indexOf(VERIFY));
    }

    @Test
    void theEraseCoversEverySectorTheWriteTouches() {
        FlashPlan plan = FlashPlan.forWrite(layout(), "update", filled(kib(64) + 1, 0xA5));
        List<FlashStep> erases = plan.stepsOf(ERASE);
        assertThat(erases).hasSize(2);
        assertThat(erases).extracting(FlashStep::address).containsExactly(0x800000L, 0x810000L);
    }

    @Test
    void theEraseStartsAtTheSectorTheRegionBeginsIn() {
        FlashPlan plan = FlashPlan.forWrite(params(0x100, kib(4)), "params", filled(100, 0x01));
        assertThat(plan.stepsOf(ERASE).getFirst().address()).isZero();
    }

    @Test
    void aFittedEraseFinishesTheTailInSubsectors() {
        FlashPlan plan =
                FlashPlan.forWrite(layout(), "update", filled(kib(64) + kib(6), 0xA5), EraseUnit.FITTED, false);
        assertThat(plan.stepsOf(ERASE).stream().map(step -> List.of(step.address(), step.length())))
                .containsExactly(List.of(0x800000L, kib(64)), List.of(0x810000L, kib(4)), List.of(0x811000L, kib(4)));
        assertThat(plan.totalMs())
                .isLessThan(FlashPlan.forWrite(layout(), "update", filled(kib(70), 0xA5), SECTOR, false)
                        .totalMs());
    }

    @Test
    void aFittedEraseTakesTheWholeSectorOnceSubsectorsWouldCostMore() {
        // three subsectors are 900ms on this part, and the sector is 700ms
        FlashPlan plan = FlashPlan.forWrite(layout(), "update", filled(kib(12) - 1, 0xA5), EraseUnit.FITTED, false);
        assertThat(plan.stepsOf(ERASE)).extracting(FlashStep::length).containsExactly(kib(64));
        FlashPlan two = FlashPlan.forWrite(layout(), "update", filled(kib(8), 0xA5), EraseUnit.FITTED, false);
        assertThat(two.stepsOf(ERASE)).extracting(FlashStep::length).containsExactly(kib(4), kib(4));
    }

    @Test
    void aFittedEraseOnAPartWithoutSubsectorsIsASectorErase() {
        FlashLayout old = FlashLayout.halves(FlashParts.part("m25p128"));
        FlashPlan plan = FlashPlan.forWrite(old, "update", filled(kib(300), 0xA5), EraseUnit.FITTED, false);
        assertThat(plan.stepsOf(ERASE))
                .extracting(FlashStep::length)
                .containsOnly(262_144L)
                .hasSize(2);
    }

    @Test
    void aFittedEraseClearsEverythingTheWriteReaches() {
        FlashPlan plan =
                FlashPlan.forWrite(params(0x100, kib(100)), "params", filled(kib(70), 0x01), EraseUnit.FITTED, true);
        long covered = plan.stepsOf(ERASE).stream().mapToLong(FlashStep::length).sum();
        assertThat(plan.stepsOf(ERASE).getFirst().address()).isZero();
        assertThat(covered).isGreaterThanOrEqualTo(0x100 + kib(70));
    }

    @Test
    void subsectorErasesAreOfferedForSmallWrites() {
        FlashPlan plan = FlashPlan.forWrite(params(0, kib(8)), "params", filled(kib(5), 0x01), SUBSECTOR, true);
        List<FlashStep> erases = plan.stepsOf(ERASE);
        assertThat(erases).hasSize(2);
        assertThat(erases).allSatisfy(step -> assertThat(step.length()).isEqualTo(4096));
    }

    @Test
    void pagesAreSplitAtPageBoundariesNotEveryPageBytes() {
        FlashPlan plan = FlashPlan.forWrite(params(0x180, kib(4)), "params", filled(600, 0x01));
        List<FlashStep> programs = plan.stepsOf(PROGRAM);
        assertThat(programs).extracting(FlashStep::length).containsExactly(128L, 256L, 216L);
        assertThat(programs).extracting(FlashStep::address).containsExactly(0x180L, 0x200L, 0x300L);
    }

    @Test
    void anAlignedImageWritesWholePages() {
        FlashPlan plan = FlashPlan.forWrite(layout(), "update", filled(768, 0x01));
        assertThat(plan.stepsOf(PROGRAM)).extracting(FlashStep::length).containsExactly(256L, 256L, 256L);
    }

    @Test
    void everyByteOfTheImageGetsProgrammed() {
        byte[] data = filled(kib(9) + 37, 0x5A);
        FlashPlan plan = FlashPlan.forWrite(layout(), "update", data);
        assertThat(plan.programmedBytes()).isEqualTo(data.length);
        assertThat(plan.imageBytes()).isEqualTo(data.length);
    }

    @Test
    void verificationReadsTheImageBackInChunks() {
        byte[] data = filled(kib(192) + 10, 0x5A);
        List<FlashStep> reads = FlashPlan.forWrite(layout(), "update", data).stepsOf(VERIFY);
        assertThat(reads).extracting(FlashStep::length).containsExactly(65536L, 65536L, 65536L, 10L);
        assertThat(reads.stream().mapToLong(FlashStep::length).sum()).isEqualTo(data.length);
    }

    @Test
    void verificationCanBeTurnedOff() {
        FlashPlan plan = FlashPlan.forWrite(layout(), "update", filled(kib(4), 0x01), SECTOR, false);
        assertThat(plan.verifies()).isFalse();
        assertThat(plan.stepsOf(VERIFY)).isEmpty();
    }

    @Test
    void theEstimateAddsUpFromTheDatasheetNumbers() {
        FlashPlan plan = FlashPlan.forWrite(layout(), "update", filled(kib(64), 0x01), SECTOR, false);
        long eraseMs = part().sectorEraseMs();
        long programMs = Math.max(1, part().pageProgramUs() / 1000) * 256L;
        assertThat(plan.totalMs()).isEqualTo(eraseMs + programMs);
        assertThat(plan.totalSeconds()).isEqualTo(Math.ceilDiv(plan.totalMs(), 1000));
    }

    @Test
    void aBiggerImageTakesLonger() {
        FlashPlan small = FlashPlan.forWrite(layout(), "update", filled(kib(64), 0x01));
        FlashPlan large = FlashPlan.forWrite(layout(), "update", filled(mib(1), 0x01));
        assertThat(large.totalMs()).isGreaterThan(small.totalMs() * 10);
    }

    @Test
    void anImageThatDoesNotFitTheRegionIsRefused() {
        ValidationError caught = catchThrowableOfType(
                ValidationError.class, () -> FlashPlan.forWrite(layout(), "update", filled(mib(5), 0x01)));
        assertThat(caught.details()).containsEntry("region", "update");
    }

    @Test
    void programmingNothingIsRefused() {
        assertThatThrownBy(() -> FlashPlan.forWrite(layout(), "update", new byte[0]))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void anErasePlanClearsTheWholeRegion() {
        FlashPlan plan = FlashPlan.forErase(layout(), "golden");
        assertThat(plan.erasedBytes()).isEqualTo(mib(4));
        assertThat(plan.programmedBytes()).isZero();
        assertThat(plan.imageBytes()).isZero();
        assertThat(plan.stepsOf(ERASE)).hasSize(64);
    }

    @Test
    void anErasePlanCanUseSubsectorsToo() {
        FlashPlan plan = FlashPlan.forErase(params(0, kib(8)), "params", SUBSECTOR);
        assertThat(plan.stepsOf(ERASE)).hasSize(2);
    }

    @Test
    void thePlanDescribesItselfForAnOperator() {
        FlashLayout single = FlashLayout.singleImage(part(), mib(1));
        String described =
                FlashPlan.forWrite(single, "golden", filled(kib(128), 0x01)).describe();
        assertThat(described).startsWith("golden on n25q128a13: 2 erases, 512 page writes");
        assertThat(described).endsWith("s");
    }

    @Test
    void aStepDescribesItself() {
        FlashStep step = FlashPlan.forWrite(layout(), "update", filled(256, 0x01))
                .stepsOf(PROGRAM)
                .getFirst();
        assertThat(step.describe()).isEqualTo("program 0x00800000 256 B ~1ms");
    }

    @Test
    void erasingARegionNobodyNamedIsRefused() {
        assertThatThrownBy(() -> FlashPlan.forErase(layout(), "scratch")).isInstanceOf(NotFound.class);
    }
}
