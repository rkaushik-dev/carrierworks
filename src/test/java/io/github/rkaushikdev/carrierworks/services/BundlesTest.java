package io.github.rkaushikdev.carrierworks.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.artifacts.ArtifactStore;
import io.github.rkaushikdev.carrierworks.bitstream.Images;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.jobs.JobMode;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import io.github.rkaushikdev.carrierworks.persistence.UnitOfWork;
import io.github.rkaushikdev.carrierworks.util.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BundlesTest {

    private static final Instant WHEN = Instant.parse("2026-04-20T09:00:00Z");

    @TempDir
    Path workspace;

    private ArtifactStore store;
    private BenchDatabase database;

    @BeforeEach
    void open() {
        store = new ArtifactStore(workspace.resolve("artifacts"));
        database = BenchDatabase.inMemory();
    }

    @AfterEach
    void close() {
        database.close();
    }

    /** Run a case in one unit of work, on a bench holding bx-14. */
    private void onBench(Consumer<UnitOfWork> body) {
        database.run(unit -> {
            Inventory.addBoard(unit, BoardSetup.kintex("bx-14").build(), WHEN.minus(Duration.ofDays(1)));
            body.accept(unit);
        });
    }

    /** The shared image, stored in the qual channel, by its digest. */
    private String ingest(UnitOfWork unit) {
        return Registry.ingest(unit, store, Images.bitstream(), WHEN, "qual").digest();
    }

    private Bundle bundle(UnitOfWork unit, String digest, String... approvals) {
        return Bundles.assemble(unit, digest, workspace.resolve("out"), WHEN, store, List.of(approvals));
    }

    private static Object json(Path path) {
        try {
            return Json.parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static Map<?, ?> manifest(Bundle made) {
        return (Map<?, ?>) json(made.manifestPath());
    }

    private static byte[] bytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @Test
    void aBundleCarriesTheImageAndAManifest() {
        onBench(unit -> {
            String digest = ingest(unit);
            Bundle made = bundle(unit, digest);
            assertThat(made.manifestPath()).isRegularFile();
            assertThat(bytes(made.root().resolve("image.bin"))).isEqualTo(store.read(digest));
        });
    }

    @Test
    void theDirectoryIsNamedAfterTheImage() {
        onBench(unit -> {
            String digest = ingest(unit);
            assertThat(bundle(unit, digest).root().getFileName()).hasToString(digest.substring(0, 12));
        });
    }

    @Test
    void everyFileIsListedInTheManifest() {
        onBench(unit -> {
            Bundle made = bundle(unit, ingest(unit));
            Set<String> written = new HashSet<>(made.files());
            written.remove(Bundles.MANIFEST);
            assertThat(((Map<?, ?>) manifest(made).get("files")).keySet()).isEqualTo(written);
        });
    }

    @Test
    void theManifestRecordsWhatTheImageIs() {
        onBench(unit -> {
            Map<?, ?> manifest = manifest(bundle(unit, ingest(unit)));
            assertThat(manifest.get("design")).isEqualTo("radar_frontend");
            assertThat(manifest.get("device")).isEqualTo("xc7k325t");
            assertThat(manifest.get("channel")).isEqualTo("qual");
        });
    }

    @Test
    void approvalsAreCarriedAndDeduplicated() {
        onBench(unit -> {
            Map<?, ?> manifest = manifest(bundle(unit, ingest(unit), "kim", "sam", "kim"));
            assertThat(manifest.get("approvals")).isEqualTo(List.of("kim", "sam"));
        });
    }

    @Test
    void aBundleForAnImageNobodyStoredIsRefused() {
        onBench(unit -> assertThatThrownBy(() -> bundle(unit, "ab".repeat(32))).isInstanceOf(NotFound.class));
    }

    @Test
    void aBundleOverAFullDirectoryIsRefused() {
        onBench(unit -> {
            String digest = ingest(unit);
            bundle(unit, digest);
            assertThatThrownBy(() -> bundle(unit, digest)).isInstanceOf(ValidationError.class);
        });
    }

    @Test
    void theLoadsOfTheImageAreWrittenDown() {
        onBench(unit -> {
            String digest = ingest(unit);
            Leasing.acquire(unit, "bx-14", "team-a", "capture", WHEN);
            Programming.submit(unit, "bx-14", digest, JobMode.JTAG, "team-a", WHEN);
            Bundle made = bundle(unit, digest);
            Map<?, ?> first = (Map<?, ?>) ((List<?>) json(made.root().resolve("jobs.json"))).getFirst();
            assertThat(made.jobs()).isEqualTo(1);
            assertThat(first.get("board")).isEqualTo("bx-14");
            assertThat(first.get("state")).isEqualTo("queued");
        });
    }

    @Test
    void anImageNobodyHasLoadedGetsAnEmptyJobList() {
        onBench(unit -> {
            Bundle made = bundle(unit, ingest(unit));
            assertThat(json(made.root().resolve("jobs.json"))).isEqualTo(List.of());
            assertThat(made.jobs()).isZero();
        });
    }

    @Test
    void theReportsGoInWhenThereAreAny() {
        onBench(unit -> {
            String digest = ingest(unit);
            Reports.ingest(unit, digest, WHEN, Images.TIMING, Images.UTILIZATION);
            Bundle made = bundle(unit, digest);
            Map<?, ?> body = (Map<?, ?>) json(made.root().resolve("reports.json"));
            assertThat(made.reports()).isEqualTo(1);
            assertThat(body.get("closed")).isEqualTo(true);
            assertThat(body.get("worst_slack_ps")).isEqualTo(412L);
        });
    }

    @Test
    void anImageWithNoReportsHasNoReportFile() {
        onBench(unit -> {
            Bundle made = bundle(unit, ingest(unit));
            assertThat(made.files()).doesNotContain("reports.json");
            assertThat(made.reports()).isZero();
        });
    }

    @Test
    void aFreshBundleVerifies() {
        onBench(unit ->
                assertThat(Bundles.verify(bundle(unit, ingest(unit)).root())).isEmpty());
    }

    @Test
    void anEditedFileIsCaught() {
        onBench(unit -> {
            Bundle made = bundle(unit, ingest(unit));
            try {
                Files.write(made.root().resolve("image.bin"), "not the image".getBytes(StandardCharsets.UTF_8));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            assertThat(Bundles.verify(made.root())).containsExactly("image.bin does not match the manifest");
        });
    }

    @Test
    void aMissingFileIsCaught() {
        onBench(unit -> {
            Bundle made = bundle(unit, ingest(unit));
            try {
                Files.delete(made.root().resolve("jobs.json"));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            assertThat(Bundles.verify(made.root())).containsExactly("jobs.json is missing");
        });
    }

    @Test
    void verifyingSomethingThatIsNotABundleIsAnError() {
        assertThatThrownBy(() -> Bundles.verify(workspace)).isInstanceOf(NotFound.class);
    }

    @Test
    void theBundleReadsAsOneLine() {
        onBench(unit -> assertThat(bundle(unit, ingest(unit)).describe()).doesNotContain("1 loads"));
    }
}
