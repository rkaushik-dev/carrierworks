package io.github.rkaushikdev.carrierworks.bitstream;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.DeviceMismatch;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.catalog.DeviceSpec;
import io.github.rkaushikdev.carrierworks.catalog.Devices;
import io.github.rkaushikdev.carrierworks.catalog.IdCode;
import io.github.rkaushikdev.carrierworks.util.Digests;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * A bitstream as the bench thinks of it: header, packets and a fingerprint.
 *
 * <p>The fingerprint is a digest of the configuration data only, not of the file. Two builds of
 * the same source produce files that differ in the date and time fields of the header and are
 * identical everywhere that matters, and the bench wants those to look like the same image,
 * because otherwise the registry fills up with duplicates and nobody can tell which one is on the
 * board.
 *
 * <p>An image whose own crc writes disagree with its stream is refused as it is read. The device
 * would refuse it too, but only after somebody had booked a board and loaded it.
 *
 * <p>The kind of image matters as much as its contents. A full image configures the whole device
 * and ends by starting it. A partial one rewrites some frames while the device carries on running,
 * and loading it into a device that is not already configured does nothing useful. They are told
 * apart here by whether the image issues the start command, which is the only difference visible
 * from outside the tools that made them.
 *
 * @param header the {@code .bit} file the image came in
 * @param packets the configuration packet stream inside it
 * @param digest the SHA-256 of the configuration data, as hex
 * @param source where the image was read from, or empty
 */
public record Bitstream(BitFile header, List<ConfigPacket> packets, String digest, String source) {

    public Bitstream {
        packets = List.copyOf(packets);
    }

    /** Parse a {@code .bit} file in memory. */
    public static Bitstream read(byte[] data) {
        return read(data, "");
    }

    /** Parse a {@code .bit} file in memory, remembering where it came from. */
    public static Bitstream read(byte[] data, String source) {
        BitFile header = BitFile.parse(data);
        if (header.length() == 0) {
            throw new ValidationError("that bit file carries no image", "bitstream", Details.of("source", source));
        }
        byte[] body = header.data();
        PacketCrc crc = PacketCrc.check(body);
        if (crc.failed() > 0) {
            throw new ValidationError(
                    "the image fails its own crc check",
                    "bitstream",
                    Details.of("source", source, "mismatch", crc.firstMismatch().orElse("")));
        }
        return new Bitstream(header, Packets.parse(body), Digests.sha256(body), source);
    }

    /** Read a {@code .bit} file off disk, named for the file it came from. */
    public static Bitstream load(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new ValidationError("no bit file there", "path", Details.of("given", path.toString()));
        }
        try {
            return read(Files.readAllBytes(path), path.getFileName().toString());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** The design name, without the attributes the tools glue on. */
    public String design() {
        return header.designName();
    }

    /** The device code the header names. */
    public String device() {
        return header.device();
    }

    /** How many bytes of configuration data there are. */
    public int length() {
        return header.length();
    }

    /** Every command the image issues, in order. */
    public List<String> commands() {
        return Packets.commandNames(packets);
    }

    /** The idcode the image itself writes, which outranks the header. */
    public Optional<IdCode> declaredIdcode() {
        return Packets.idcodeFrom(packets).map(IdCode::new);
    }

    /** A partial image never starts the device; it assumes it is running. */
    public boolean isPartial() {
        return !commands().contains("start");
    }

    /** The device's own crc check, run over the configuration data. */
    public PacketCrc crc() {
        return PacketCrc.check(header.data());
    }

    /** The configuration options the image sets on the device. */
    public ConfigOptions options() {
        return ConfigOptions.read(packets);
    }

    /** An image that can jump to another one carries the {@code iprog} command. */
    public boolean isMultiboot() {
        return options().jumpsTo().isPresent();
    }

    /** The first twelve digits of the digest, which is how people quote an image. */
    public String shortDigest() {
        return digest.substring(0, Math.min(12, digest.length()));
    }

    /** The catalog entry for the device the header names, if the lab stocks it. */
    public Optional<DeviceSpec> spec() {
        return Devices.lookup(device());
    }

    /**
     * How much of the device's configuration space this image covers.
     *
     * <p>A full image is essentially all of it. Anything much under that and the image is partial
     * whatever its commands say, which is worth being able to see.
     */
    public double fills() {
        Optional<DeviceSpec> spec = spec();
        if (spec.isEmpty() || spec.get().configBytes() == 0) {
            return 0.0;
        }
        return (double) length() / spec.get().configBytes();
    }

    /**
     * Refuse unless this image belongs on that device.
     *
     * <p>The image's own idcode write is believed over its header, because the header is a string a
     * tool wrote and the idcode is what the device will compare against at load time. If they
     * disagree that is worth knowing too, and it is checked first.
     *
     * @throws DeviceMismatch when the image is for another device
     */
    public void checkFor(String device) {
        DeviceSpec target = Devices.deviceFor(device);
        Optional<IdCode> declared = declaredIdcode();
        if (declared.isPresent() && !target.matchesIdcode(declared.get().value())) {
            throw new DeviceMismatch(
                    "the image writes an idcode for another device",
                    target.device(),
                    declared.get().toString(),
                    Details.of("design", design()));
        }
        if (declared.isEmpty() && !device().equals(target.device())) {
            throw new DeviceMismatch(
                    "the image header names another device", target.device(), device(), Details.of("design", design()));
        }
    }

    /** {@code radar_frontend full for xc7a100t 3825788 bytes 1f2e3d4c5b6a}: the image as a log line. */
    public String summary() {
        String kind = isPartial() ? "partial" : "full";
        return design() + " " + kind + " for " + device() + " " + length() + " bytes " + shortDigest();
    }
}
