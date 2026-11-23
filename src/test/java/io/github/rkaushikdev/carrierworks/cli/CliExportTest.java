package io.github.rkaushikdev.carrierworks.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.carrierworks.bitstream.Binary;
import io.github.rkaushikdev.carrierworks.bitstream.HexRegion;
import io.github.rkaushikdev.carrierworks.bitstream.Images;
import io.github.rkaushikdev.carrierworks.bitstream.Mcs;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import io.github.rkaushikdev.carrierworks.services.Inventory;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliExportTest {

    @TempDir
    Path root;

    private BenchDatabase database;
    private Shell shell;

    @BeforeEach
    void workspace() throws IOException {
        String url = Shell.url(root);
        database = new BenchDatabase(Shell.settings(url).toBuilder()
                .artifactRoot(root.resolve("artifacts"))
                .build());
        database.createSchema();
        Files.write(root.resolve("radar.bit"), Images.bitfile());
        shell = new Shell(url)
                .env("FPGA_ARTIFACT_ROOT", root.resolve("artifacts").toString());
    }

    @AfterEach
    void close() {
        database.close();
    }

    private String digest() {
        return shell.run("--json", "image", "upload", root.resolve("radar.bit").toString())
                .json()
                .get("digest")
                .asText();
    }

    private Shell.Result exportTo(String digest, String name, String... extra) {
        List<String> args = new ArrayList<>(List.of(
                "--json",
                "image",
                "export",
                digest,
                "--into",
                root.resolve(name).toString()));
        args.addAll(List.of(extra));
        return shell.run(args.toArray(String[]::new));
    }

    private Shell.Result export(String digest, String... extra) {
        return exportTo(digest, "out.bin", extra);
    }

    private byte[] bytes(String name) throws IOException {
        return Files.readAllBytes(root.resolve(name));
    }

    private String text(String name) throws IOException {
        return Files.readString(root.resolve(name), StandardCharsets.UTF_8);
    }

    @Test
    void anImageComesOutAsBytes() throws IOException {
        JsonNode body = export(digest()).json();
        byte[] written = bytes("out.bin");
        assertThat(body.get("format").asText()).isEqualTo("bin");
        assertThat(body.get("bytes").numberValue()).isEqualTo(written.length);
    }

    @Test
    void theExportedBytesAreTheStoredOnes() throws IOException {
        String digest = digest();
        export(digest);
        exportTo(digest, "again.bin");
        assertThat(bytes("out.bin")).isEqualTo(bytes("again.bin"));
    }

    @Test
    void anImageCanComeOutAsHex() throws IOException {
        JsonNode body = exportTo(digest(), "out.mcs", "--format", "mcs").json();
        String text = text("out.mcs");
        assertThat(body.get("format").asText()).isEqualTo("mcs");
        assertThat(text).startsWith(":");
        assertThat(text.stripTrailing()).endsWith(":00000001FF");
    }

    @Test
    void theHexFileReadsBackAsTheSameBytes() throws IOException {
        String digest = digest();
        exportTo(digest, "plain.bin");
        exportTo(digest, "out.mcs", "--format", "mcs");
        HexRegion flat = Mcs.flatten(Mcs.read(text("out.mcs")));
        assertThat(flat.start()).isZero();
        assertThat(flat.data()).isEqualTo(bytes("plain.bin"));
    }

    @Test
    void aHexFileCarriesTheAddressItLoadsAt() throws IOException {
        exportTo(digest(), "update.mcs", "--format", "mcs", "--at", "0x800000");
        HexRegion flat = Mcs.flatten(Mcs.read(text("update.mcs")));
        assertThat(flat.start()).isEqualTo(0x800000L);
    }

    @Test
    void theAddressCanBeWrittenInDecimal() {
        JsonNode body = export(digest(), "--at", "4096").json();
        assertThat(body.get("address").numberValue()).isEqualTo(4096);
    }

    @Test
    void theAddressCanBeWrittenAsASize() {
        assertThat(export(digest(), "--at", "8MiB").json().get("address").numberValue())
                .isEqualTo(0x80_0000);
        assertThat(export(digest(), "--at", "0b1_0000_0000_0000")
                        .json()
                        .get("address")
                        .numberValue())
                .isEqualTo(4096);
    }

    @Test
    void theAddressCanBeARegionOfABoardsFlash() {
        database.run(unit -> Inventory.addBoard(
                unit, BoardSetup.kintex("bx-14").build(), Times.now().minus(Duration.ofDays(1))));
        JsonNode body = exportTo(digest(), "update.mcs", "--format", "mcs", "--at", "update", "--board", "bx-14")
                .json();
        assertThat(body.get("address").numberValue()).isEqualTo(0x80_0000);
        assertThat(body.get("region").asText()).isEqualTo("update");
        Shell.Result plain = shell.run(
                "image",
                "export",
                digest(),
                "--into",
                root.resolve("golden.bin").toString(),
                "--at",
                "golden",
                "--board",
                "bx-14");
        assertThat(plain.output()).contains("for the golden region");
    }

    @Test
    void aRegionWithNoBoardIsAUsageError() {
        Shell.Result result = export(digest(), "--at", "update");
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains("give --board");
    }

    @Test
    void anImageThatRunsOffTheEndOfItsRegionIsRefused() {
        database.run(unit -> Inventory.addBoard(
                unit, BoardSetup.kintex("bx-14").build(), Times.now().minus(Duration.ofDays(1))));
        Shell.Result result = export(digest(), "--at", "0x7fff00", "--board", "bx-14", "--pad-to", "4KiB");
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("runs past the end of the golden region");
    }

    @Test
    void anAddressThatIsNeitherANumberNorASizeIsAUsageError() {
        Shell.Result result = export(digest(), "--at", "12 parsecs");
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains("a whole number, a size or a region");
    }

    @Test
    void aNegativeAddressIsAUsageError() {
        Shell.Result result = export(digest(), "--at", "-1");
        assertThat(result.exitCode()).isNotZero();
    }

    @Test
    void anUnknownFormatIsAUsageError() {
        Shell.Result result = export(digest(), "--format", "srec");
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("bin or mcs");
    }

    @Test
    void anImageCanBePaddedOut() throws IOException {
        JsonNode body = export(digest(), "--pad-to", "4096").json();
        assertThat(body.get("bytes").numberValue()).isEqualTo(4096);
        byte[] written = bytes("out.bin");
        assertThat(written[written.length - 1]).isEqualTo((byte) 0xFF);
    }

    @Test
    void paddingCanBeWrittenAsASize() throws IOException {
        JsonNode body = export(digest(), "--pad-to", "4KiB").json();
        assertThat(body.get("bytes").numberValue()).isEqualTo(4096);
        assertThat(export(digest(), "--pad-to", "lots").exitCode()).isEqualTo(2);
    }

    @Test
    void paddingSmallerThanTheImageIsRefused() {
        assertThat(export(digest(), "--pad-to", "4").exitCode()).isNotZero();
    }

    @Test
    void theSpiFlagBitReversesEveryByte() throws IOException {
        String digest = digest();
        exportTo(digest, "plain.bin");
        exportTo(digest, "spi.bin", "--spi");
        assertThat(bytes("spi.bin")).isEqualTo(Binary.reverseBits(bytes("plain.bin")));
    }

    @Test
    void exportingAnImageNobodyHasIsAnError() {
        assertThat(export("ab".repeat(32)).exitCode()).isNotZero();
    }
}
