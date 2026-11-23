package io.github.rkaushikdev.carrierworks.bitstream;

import java.util.ArrayList;
import java.util.List;

/**
 * Bitstreams and build reports the tests share.
 *
 * <p>Six test classes were each building their own {@code .bit} file out of the same nine packet
 * words, with the dates drifting apart and one of them quietly producing a partial image because a
 * line got lost in a copy. The builder is one place now, and the reports with it, because a test
 * that reads a report almost always uploads an image first.
 *
 * <p>Nothing here is shared state. {@link #image()} hands out a fresh description each time, so a
 * test that wants a second image with one thing changed asks for it:
 * {@code Images.image().frames(20).start(false).bitfile()}.
 */
public final class Images {

    /** The part field of the capture carrier's Kintex. */
    public static final String KINTEX_PART = "7k325tffg900";

    /** The part field of the small carrier's Artix. */
    public static final String ARTIX_PART = "7a100tfgg484";

    /** The idcode the shared image writes unless told otherwise: the Kintex. */
    public static final long KINTEX_IDCODE = 0x03651093L;

    /** A timing summary that closes with room to spare. */
    public static final String TIMING =
            """

            Design Timing Summary
            Design : radar_frontend
            | WNS(ns) | TNS(ns) | TNS Failing | TNS Total | WHS(ns) | THS(ns) | THS Failing | THS Total |
            | 0.412 | 0.000 | 0 | 118423 | 0.137 | 0.000 | 0 | 118423 |
            """;

    /** A timing summary that closes, but only just. */
    public static final String TIGHT_TIMING =
            """

            | WNS(ns) | TNS(ns) | TNS Failing | TNS Total | WHS(ns) | THS(ns) | THS Failing | THS Total |
            | 0.021 | 0.000 | 0 | 118423 | 0.005 | 0.000 | 0 | 118423 |
            """;

    /** A timing summary with failing setup paths. */
    public static final String FAILING_TIMING =
            """

            | WNS(ns) | TNS(ns) | TNS Failing | TNS Total | WHS(ns) | THS(ns) | THS Failing | THS Total |
            | -0.311 | -14.200 | 96 | 118423 | 0.137 | 0.000 | 0 | 118423 |
            """;

    /** A utilization table for a design that sits comfortably in the Kintex. */
    public static final String UTILIZATION =
            """

            +----------------------------+-------+-------+-----------+-------+
            |          Site Type         |  Used | Fixed | Available | Util% |
            +----------------------------+-------+-------+-----------+-------+
            | Slice LUTs                 | 45123 |     0 |    203800 | 22.14 |
            | Slice Registers            | 61234 |     0 |    407600 | 15.02 |
            +----------------------------+-------+-------+-----------+-------+
            """;

    private Images() {}

    /** A fresh description of the shared image, to change before asking for its bytes. */
    public static Image image() {
        return new Image();
    }

    /** The packet stream inside the shared full configuration image. */
    public static byte[] configWords() {
        return image().configWords();
    }

    /** The same stream with a different number of frame words. */
    public static byte[] configWords(int frames) {
        return image().frames(frames).configWords();
    }

    /** A stream with its frame count, start command and idcode chosen. */
    public static byte[] configWords(int frames, boolean start, long idcode) {
        return image().frames(frames).start(start).idcode(idcode).configWords();
    }

    /** The shared image as a {@code .bit} file, with a real header and a real packet stream. */
    public static byte[] bitfile() {
        return image().bitfile();
    }

    /** The shared image as a {@code .bit} file, with a different number of frame words. */
    public static byte[] bitfile(int frames) {
        return image().frames(frames).bitfile();
    }

    /** The shared image, read back. */
    public static Bitstream bitstream() {
        return image().bitstream();
    }

    /** One image to build: everything the shared one says, unless a test changes it. */
    public static final class Image {

        private String design = "radar_frontend";
        private String part = KINTEX_PART;
        private String date = "2026/01/15";
        private String time = "02:00:00";
        private int frames = 16;
        private boolean start = true;
        private long idcode = KINTEX_IDCODE;
        private final List<Long> options = new ArrayList<>();
        private final List<Long> trailer = new ArrayList<>();

        private Image() {}

        /** The design name, before the tool version is glued on. */
        public Image design(String value) {
            design = value;
            return this;
        }

        /** The part field of the header. */
        public Image part(String value) {
            part = value;
            return this;
        }

        /** The date field of the header. */
        public Image date(String value) {
            date = value;
            return this;
        }

        /** The time field of the header. */
        public Image time(String value) {
            time = value;
            return this;
        }

        /** How many frame words the image writes. */
        public Image frames(int value) {
            frames = value;
            return this;
        }

        /** Whether the image ends by starting the device; one that does not is partial. */
        public Image start(boolean value) {
            start = value;
            return this;
        }

        /** The idcode the image writes. */
        public Image idcode(long value) {
            idcode = value;
            return this;
        }

        /** Switch the flash to this read opcode once the device has read the start of the image. */
        public Image spiRead(int opcode) {
            return write(31, opcode);
        }

        /** Turn the decryptor on, masked the way the tools write it. */
        public Image encrypted() {
            write(6, 1L << 6);
            return write(5, 1L << 6);
        }

        /** Arm the configuration watchdog with this count. */
        public Image watchdog(long cycles) {
            return write(17, (1L << 30) | cycles);
        }

        /** Issue a command ahead of the frames. */
        public Image command(long code) {
            return write(4, code);
        }

        /** Send the device to another flash address ahead of the frames, the way a golden image does. */
        public Image iprog(long address) {
            write(16, address);
            return command(15);
        }

        /** End the stream with a desync, after the start command, the way the tools do. */
        public Image desync() {
            trailer.add(Packets.type1(2, 4, 1));
            trailer.add(13L);
            return this;
        }

        /** One more register write ahead of the frames. */
        public Image write(int register, long word) {
            options.add(Packets.type1(2, register, 1));
            options.add(word);
            return this;
        }

        /** The packet stream inside the image. */
        public byte[] configWords() {
            List<Long> words =
                    new ArrayList<>(List.of(0xFFFF_FFFFL, Packets.SYNC_WORD, Packets.type1(2, 12, 1), idcode));
            words.addAll(options);
            words.addAll(List.of(Packets.type1(2, 2, 0), Packets.type2(2, frames)));
            for (long word = 0; word < frames; word++) {
                words.add(word);
            }
            if (start) {
                words.add(Packets.type1(2, 4, 1));
                words.add(5L);
            }
            words.addAll(trailer);
            return Packets.wordsToBytes(words);
        }

        /** The image as a {@code .bit} file. */
        public byte[] bitfile() {
            return BitFile.build(design + ";Version=2024.1", part, date, time, configWords());
        }

        /** The image, read back. */
        public Bitstream bitstream() {
            return Bitstream.read(bitfile());
        }
    }
}
