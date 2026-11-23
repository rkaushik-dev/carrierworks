package io.github.rkaushikdev.carrierworks.bitstream;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The configuration options an image sets on the device, read out of its register writes.
 *
 * <p>These are the bitstream settings a designer picks in the constraints file and then forgets,
 * and every one of them changes what the bench can do with the image. The SPI read decides which
 * flash parts and which addresses the image can boot from. Encryption means the device will not
 * let anybody read the configuration back, so a verify after programming can only fail.
 * Compression means the image is much shorter than the device's configuration and still covers all
 * of it. The watchdog decides whether a board that loads a corrupt update falls back to the golden
 * image or sits there with its done light off.
 *
 * <p>Control register writes are masked: the device only takes the bits the last {@code mask}
 * write allowed. The tools always write the mask first, and a write with no mask before it is read
 * as if every bit were allowed.
 *
 * @param spiRead the read the image switches the flash to, or empty when it never says
 * @param encrypted whether the image turns the decryptor on
 * @param compressed whether the image writes frames through the multiple frame register
 * @param watchdogCycles the configuration watchdog's count, or empty when it is not armed
 */
public record ConfigOptions(
        Optional<SpiRead> spiRead, boolean encrypted, boolean compressed, OptionalLong watchdogCycles) {

    /** What an image that sets nothing leaves the device at. */
    public static final ConfigOptions DEFAULTS =
            new ConfigOptions(Optional.empty(), false, false, OptionalLong.empty());

    private static final long DECRYPT_BIT = 1L << 6;
    private static final long WATCHDOG_ON_CONFIG = 1L << 30;
    private static final long WATCHDOG_COUNT = 0x3FFF_FFFFL;
    private static final long ALL_BITS = 0xFFFF_FFFFL;

    /** Read the options out of the configuration data a store keeps. */
    public static ConfigOptions of(byte[] configData) {
        return read(Packets.parse(configData));
    }

    /** Read the options out of a packet stream, in stream order. */
    public static ConfigOptions read(List<ConfigPacket> packets) {
        Optional<SpiRead> spiRead = Optional.empty();
        boolean encrypted = false;
        boolean compressed = false;
        OptionalLong watchdog = OptionalLong.empty();
        long mask = ALL_BITS;
        for (ConfigPacket packet : packets) {
            if (!packet.isWrite() || packet.words().isEmpty()) {
                continue;
            }
            long word = packet.words().getLast();
            switch (packet.registerName()) {
                case "mask" -> mask = word;
                case "ctl0" -> encrypted = (word & mask & DECRYPT_BIT) != 0;
                case "mfwr" -> compressed = true;
                case "bspi" -> spiRead = Optional.of(SpiRead.of((int) (word & 0xFF)));
                case "timer" ->
                    watchdog = (word & WATCHDOG_ON_CONFIG) != 0 && (word & WATCHDOG_COUNT) != 0
                            ? OptionalLong.of(word & WATCHDOG_COUNT)
                            : OptionalLong.empty();
                default -> {}
            }
        }
        return new ConfigOptions(spiRead, encrypted, compressed, watchdog);
    }

    /**
     * The read the device ends up using: the one the image asks for, or the plain one byte wide,
     * three address byte read it starts in when the image never asks.
     */
    public SpiRead effectiveRead() {
        return spiRead.orElse(SpiRead.READ);
    }

    /** Whether a configuration watchdog is armed, which is what makes a bad update fall back. */
    public boolean watchdog() {
        return watchdogCycles.isPresent();
    }

    /** Whether the device will allow the configuration to be read back after it is loaded. */
    public boolean readable() {
        return !encrypted;
    }

    /** {@code x4 read 0x6b, 3 address bytes, encrypted, watchdog 0x00100000}, or {@code defaults}. */
    public String describe() {
        List<String> parts = new ArrayList<>();
        spiRead.ifPresent(read -> parts.add(read.describe()));
        if (encrypted) {
            parts.add("encrypted");
        }
        if (compressed) {
            parts.add("compressed");
        }
        watchdogCycles.ifPresent(cycles -> parts.add(String.format(Locale.ROOT, "watchdog 0x%08x", cycles)));
        return parts.isEmpty() ? "defaults" : String.join(", ", parts);
    }
}
