package io.github.rkaushikdev.carrierworks.jobs;

import static io.github.rkaushikdev.carrierworks.Measure.mib;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.carrierworks.artifacts.ArtifactKind;
import io.github.rkaushikdev.carrierworks.artifacts.ArtifactRecord;
import io.github.rkaushikdev.carrierworks.bitstream.ConfigOptions;
import io.github.rkaushikdev.carrierworks.bitstream.SpiRead;
import io.github.rkaushikdev.carrierworks.flash.FlashLayout;
import io.github.rkaushikdev.carrierworks.flash.FlashPlan;
import io.github.rkaushikdev.carrierworks.flash.WearLedger;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.inventory.BoardState;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatesTest {

    private static final Instant NOW = Instant.parse("2025-10-06T10:00:00Z");
    private static final String DIGEST = "cd".repeat(32);
    private static final String DEVICE = "xc7k325t";
    private static final long LENGTH = 11_000_000;

    @TempDir
    Path tmp;

    private static Board board() {
        return BoardSetup.kintex("bx-14").build();
    }

    /** A full image for the fitted device, in qual. */
    private static ArtifactRecord record() {
        return record(DEVICE, LENGTH, ArtifactKind.FULL);
    }

    private static ArtifactRecord record(String device, long length, ArtifactKind kind) {
        return new ArtifactRecord(DIGEST, "radar_frontend", device, length, kind).withChannel("qual");
    }

    private static ProgramJob job() {
        return job(JobMode.JTAG);
    }

    private static ProgramJob job(JobMode mode) {
        return ProgramJob.builder("job-00001", "bx-14", DIGEST, mode, "team-a", NOW)
                .build();
    }

    private static Lease lease() {
        return lease("team-a");
    }

    private static Lease lease(String holder) {
        return new Lease(
                "lease-00001",
                "bx-14",
                holder,
                "capture",
                NOW.minus(Duration.ofMinutes(5)),
                NOW.plus(Duration.ofHours(1)));
    }

    private static BoardState leased() {
        return new BoardState(BoardStatus.LEASED, NOW.minus(Duration.ofMinutes(5)));
    }

    /** The checks for a job held by whoever asked for it, with timing closed. */
    private static Gates.Check check(ProgramJob live, Board fitted, ArtifactRecord image, BoardState state) {
        return Gates.check(live, fitted, image, state).lease(lease()).timingClosed(true);
    }

    private static Gates.Check check() {
        return check(job(), board(), record(), leased());
    }

    private static Gates.Check check(ProgramJob live) {
        return check(live, board(), record(), leased());
    }

    private static Gates.Check check(ArtifactRecord image) {
        return check(job(), board(), image, leased());
    }

    private static Gates.Check check(ProgramJob live, ArtifactRecord image) {
        return check(live, board(), image, leased());
    }

    private static Gates.Check check(ProgramJob live, Board fitted) {
        return check(live, fitted, record(), leased());
    }

    private static Gates.Check check(BoardState state) {
        return check(job(), board(), record(), state);
    }

    private static byte[] ones(int length) {
        byte[] data = new byte[length];
        Arrays.fill(data, (byte) 0x01);
        return data;
    }

    @Test
    void aCleanJtagJobIsAllowed() {
        GateReport report = check().report();
        assertThat(report.allowed()).isTrue();
        assertThat(report.describe()).isEqualTo("job-00001 may run");
    }

    @Test
    void aJobOnABoardNobodyHoldsIsRefused() {
        GateReport report = check().lease(Optional.empty()).report();
        assertThat(report.blockers()).contains("nobody holds a lease on that board");
    }

    @Test
    void aJobOnSomebodyElseSBoardIsRefused() {
        GateReport report = check().lease(lease("team-b")).report();
        assertThat(report.blockers()).contains("the board is leased to team-b");
    }

    @Test
    void aJobOnAFaultedBoardIsRefused() {
        BoardState state = new BoardState(BoardStatus.FAULTED, NOW, "chain");
        GateReport report = check(state).report();
        assertThat(report.blockers()).contains("the board is faulted");
    }

    @Test
    void aJobWhileTheBoardIsAlreadyProgrammingIsAllowed() {
        BoardState state = new BoardState(BoardStatus.PROGRAMMING, NOW);
        assertThat(check(state).report().allowed()).isTrue();
    }

    @Test
    void anImageForAnotherDeviceIsRefused() {
        GateReport report = check(record("xc7a100t", LENGTH, ArtifactKind.FULL)).report();
        assertThat(report.blockers()).contains("the image is for xc7a100t and the board carries xc7k325t");
    }

    @Test
    void anImageLongerThanTheDeviceConfigurationIsRefused() {
        GateReport report =
                check(record(DEVICE, 200_000_000, ArtifactKind.FULL)).report();
        assertThat(report.blockers()).anyMatch(blocker -> blocker.contains("longer than this device"));
    }

    @Test
    void aDevImageCanBeLoadedOverJtag() {
        assertThat(check(record().withChannel("dev")).report().allowed()).isTrue();
    }

    @Test
    void aDevImageCannotBeWrittenToFlash() {
        GateReport report =
                check(job(JobMode.FLASH_UPDATE), record().withChannel("dev")).report();
        assertThat(report.blockers()).anyMatch(blocker -> blocker.contains("takes qual or golden images"));
    }

    @Test
    void aQualifiedImageCanGoInTheUpdateRegion() {
        assertThat(check(job(JobMode.FLASH_UPDATE)).report().allowed()).isTrue();
    }

    @Test
    void onlyAGoldenImageGoesInTheGoldenRegion() {
        GateReport refused = check(job(JobMode.FLASH_GOLDEN)).report();
        assertThat(refused.blockers()).anyMatch(blocker -> blocker.contains("takes golden images"));
        assertThat(check(job(JobMode.FLASH_GOLDEN), record().withChannel("golden"))
                        .report()
                        .allowed())
                .isTrue();
    }

    @Test
    void aPartialImageCannotBeWrittenToFlash() {
        GateReport report = check(job(JobMode.FLASH_UPDATE), record(DEVICE, LENGTH, ArtifactKind.PARTIAL))
                .report();
        assertThat(report.blockers()).contains("a partial image cannot be written to flash");
    }

    @Test
    void aPartialImageCanStillBeLoadedOverJtag() {
        assertThat(check(record(DEVICE, LENGTH, ArtifactKind.PARTIAL)).report().allowed())
                .isTrue();
    }

    @Test
    void anImageBiggerThanTheFlashIsRefused() {
        GateReport report = check(job(JobMode.FLASH_UPDATE), record(DEVICE, mib(20), ArtifactKind.FULL))
                .report();
        assertThat(report.blockers()).contains("the image is bigger than the flash on this board");
    }

    @Test
    void aFlashWriteNeedsATimingReport() {
        GateReport report =
                check(job(JobMode.FLASH_UPDATE)).timingClosed(Optional.empty()).report();
        assertThat(report.blockers()).contains("no timing report has been read for this image");
    }

    @Test
    void aDesignThatMissedTimingDoesNotGoInFlash() {
        GateReport report = check(job(JobMode.FLASH_UPDATE)).timingClosed(false).report();
        assertThat(report.blockers()).contains("the design did not close timing");
    }

    @Test
    void timingIsNotCheckedForAJtagLoad() {
        assertThat(check().timingClosed(Optional.empty()).report().allowed()).isTrue();
    }

    @Test
    void theTimingGateCanBeTurnedOffForASiteThatDoesNotBuild() {
        GateReport report = check(job(JobMode.FLASH_UPDATE))
                .timingClosed(Optional.empty())
                .requireTiming(false)
                .report();
        assertThat(report.allowed()).isTrue();
    }

    @Test
    void aWornFlashDoesNotGetTheGoldenImage() {
        WearLedger ledger = new WearLedger(tmp.resolve("unused.json"));
        ledger.restore("bx-14", Map.of(0L, 95_000L));
        GateReport report = check(job(JobMode.FLASH_GOLDEN), record().withChannel("golden"))
                .wear(ledger)
                .report();
        assertThat(report.blockers().getFirst()).contains("near the end of its erase life");
    }

    @Test
    void aFreshFlashTakesTheGoldenImage() {
        WearLedger ledger = new WearLedger(tmp.resolve("unused.json"));
        ledger.record(
                "bx-14",
                FlashPlan.forWrite(FlashLayout.singleImage(board().flashSpec(), mib(4)), "golden", ones(4096)));
        GateReport report = check(job(JobMode.FLASH_GOLDEN), record().withChannel("golden"))
                .wear(ledger)
                .report();
        assertThat(report.allowed()).isTrue();
    }

    @Test
    void aCableThatCannotWriteFlashStopsAFlashJob() {
        Board slow = BoardSetup.kintex("bx-14").adapterModel("arm-usb-tiny-h").build();
        GateReport report = check(job(JobMode.FLASH_UPDATE), slow).report();
        assertThat(report.blockers()).anyMatch(blocker -> blocker.contains("cannot write flash"));
    }

    @Test
    void aBoardWithNoCableCannotTakeAFlashJob() {
        GateReport report = check(
                        job(JobMode.FLASH_UPDATE),
                        BoardSetup.kintex("bx-14").noCable().build())
                .report();
        assertThat(report.blockers()).contains("no cable is on that board");
    }

    private static Board oneBitFlash() {
        return BoardSetup.kintex("bx-14").flash("m25p128").build();
    }

    private static ConfigOptions reading(SpiRead read) {
        return new ConfigOptions(Optional.of(read), false, false, OptionalLong.empty());
    }

    @Test
    void aQuadReadImageCannotGoOnAFlashWiredOneBitWide() {
        GateReport report = check(job(JobMode.FLASH_UPDATE), oneBitFlash())
                .options(reading(SpiRead.QUAD_READ))
                .report();
        assertThat(report.blockers())
                .containsExactly("the image reads its flash four bits wide and the m25p128 on this board cannot");
    }

    @Test
    void aQuadReadImageGoesOnAQuadPart() {
        assertThat(check(job(JobMode.FLASH_UPDATE))
                        .options(reading(SpiRead.QUAD_READ_4B))
                        .report()
                        .allowed())
                .isTrue();
    }

    @Test
    void aOneOrTwoBitReadGoesOnAnyPart() {
        for (SpiRead read : new SpiRead[] {SpiRead.FAST_READ, SpiRead.DUAL_READ, SpiRead.READ_4B}) {
            assertThat(check(job(JobMode.FLASH_UPDATE), oneBitFlash())
                            .options(reading(read))
                            .report()
                            .allowed())
                    .as(read.name())
                    .isTrue();
        }
    }

    @Test
    void theReadWidthDoesNotMatterForAJtagLoad() {
        assertThat(check(job(), oneBitFlash())
                        .options(reading(SpiRead.QUAD_READ))
                        .report()
                        .allowed())
                .isTrue();
    }

    @Test
    void anImageThatNeverNamesARead() {
        assertThat(check(job(JobMode.FLASH_UPDATE), oneBitFlash())
                        .options(ConfigOptions.DEFAULTS)
                        .report()
                        .allowed())
                .isTrue();
    }

    @Test
    void everyBlockerIsReportedAtOnce() {
        GateReport report = check(
                        job(JobMode.FLASH_GOLDEN),
                        record("xc7a100t", LENGTH, ArtifactKind.PARTIAL).withChannel("dev"))
                .lease(Optional.empty())
                .timingClosed(false)
                .report();
        assertThat(report.blockers()).hasSizeGreaterThanOrEqualTo(5);
        assertThat(report.describe()).startsWith("job-00001 refused: ");
    }
}
