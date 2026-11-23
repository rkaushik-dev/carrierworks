package io.github.rkaushikdev.carrierworks.jobs;

import io.github.rkaushikdev.carrierworks.artifacts.ArtifactRecord;
import io.github.rkaushikdev.carrierworks.artifacts.Channel;
import io.github.rkaushikdev.carrierworks.bitstream.ConfigOptions;
import io.github.rkaushikdev.carrierworks.catalog.FlashSpec;
import io.github.rkaushikdev.carrierworks.flash.FlashLayout;
import io.github.rkaushikdev.carrierworks.flash.FlashRegion;
import io.github.rkaushikdev.carrierworks.flash.WearLedger;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardState;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Everything checked before a programming job is allowed to touch a board.
 *
 * <p>This is the code that stops the expensive mistakes, so it is written as a list of independent
 * checks that all run rather than as a chain that stops at the first no. Somebody whose job is
 * refused wants the whole list, not one item of it at a time across three attempts.
 *
 * <p>The checks, in order of how much they have cost this lab:
 *
 * <ul>
 *   <li>The image has to be for the device that is fitted. This is checked against the idcode the
 *       image writes, which is where its record's device comes from, not against its file name.
 *   <li>A flash write has to be an image the bench trusts. Anything can be loaded over JTAG, because
 *       it disappears at the next power cycle. The update region takes qualified images and the
 *       golden region takes only images somebody has signed off, because a bad golden image is a
 *       board that has to come out of the rack.
 *   <li>The board has to be held by whoever is asking. Programming a board somebody else is using is
 *       indistinguishable, from their side, from the board failing.
 *   <li>A worn flash does not get the golden image. If the part is near the end of its erase life,
 *       the copy that has to survive is not the one to write to it.
 *   <li>An image has to be able to read itself back out of the part it is written to. One built for
 *       a quad read boots from nothing on a part that is not wired for four bit transfers, and one
 *       written above sixteen megabytes has to be read with four address bytes or the device never
 *       finds it.
 *   <li>A golden image that jumps has to land on the update image. A jump anywhere else boots a
 *       stale image, or nothing, and the board looks as if the update failed.
 * </ul>
 *
 * <p>The blockers are sentences operators read, and {@link GateReport#describe()} puts them in one
 * line. Each one is reported with the {@link Gate} behind it, in gate order.
 */
public final class Gates {

    private Gates() {}

    /**
     * Start the checks for one job against the board, image and board state it would run with.
     *
     * <p>Unless the check is told otherwise, nobody holds a lease on the board, no timing report has
     * been read, timing closure is required for a flash write, and no wear ledger is consulted.
     */
    public static Check check(ProgramJob job, Board board, ArtifactRecord record, BoardState state) {
        return new Check(job, board, record, state);
    }

    /** Which channels a mode will take images from, least trusted first. */
    private static List<Channel> allowedChannels(JobMode mode) {
        return switch (mode) {
            case JTAG -> List.of(Channel.DEV, Channel.QUAL, Channel.GOLDEN);
            case FLASH_UPDATE -> List.of(Channel.QUAL, Channel.GOLDEN);
            case FLASH_GOLDEN -> List.of(Channel.GOLDEN);
        };
    }

    /** The checks for one job, with whatever else is known about the board, run by {@link #report()}. */
    public static final class Check {

        private final ProgramJob job;
        private final Board board;
        private final ArtifactRecord record;
        private final BoardState state;
        private Optional<Lease> lease = Optional.empty();
        private Optional<Boolean> timingClosed = Optional.empty();
        private boolean requireTiming = true;
        private Optional<WearLedger> wear = Optional.empty();
        private ConfigOptions options = ConfigOptions.DEFAULTS;

        private Check(ProgramJob job, Board board, ArtifactRecord record, BoardState state) {
            this.job = Objects.requireNonNull(job, "job");
            this.board = Objects.requireNonNull(board, "board");
            this.record = Objects.requireNonNull(record, "record");
            this.state = Objects.requireNonNull(state, "state");
        }

        /** The live lease on the board. */
        public Check lease(Lease value) {
            return lease(Optional.of(value));
        }

        /** The live lease on the board, or empty when nobody holds one. */
        public Check lease(Optional<Lease> value) {
            lease = Objects.requireNonNull(value, "lease");
            return this;
        }

        /** Whether the build report for the image says it closed timing. */
        public Check timingClosed(boolean value) {
            return timingClosed(Optional.of(value));
        }

        /** Whether the image closed timing, or empty when no report has been read for it. */
        public Check timingClosed(Optional<Boolean> value) {
            timingClosed = Objects.requireNonNull(value, "timingClosed");
            return this;
        }

        /** Whether a flash write needs a report saying timing closed; a site that does not build turns it off. */
        public Check requireTiming(boolean value) {
            requireTiming = value;
            return this;
        }

        /** The wear ledger to ask whether the board's flash is worn. */
        public Check wear(WearLedger value) {
            return wear(Optional.of(value));
        }

        /** The wear ledger, or empty to leave wear out of it. */
        public Check wear(Optional<WearLedger> value) {
            wear = Objects.requireNonNull(value, "wear");
            return this;
        }

        /** What the image sets on the device, which is the defaults unless the check is told. */
        public Check options(ConfigOptions value) {
            options = Objects.requireNonNull(value, "options");
            return this;
        }

        /** Run every gate that applies to the job and report all of them at once, in gate order. */
        public GateReport report() {
            List<String> blockers = new ArrayList<>();
            List<Gate> refusing = new ArrayList<>();
            for (Gate gate : Gate.values()) {
                if (gate.flashOnly() && !job.writesFlash()) {
                    continue;
                }
                for (String blocker : blockers(gate)) {
                    blockers.add(blocker);
                    refusing.add(gate);
                }
            }
            return new GateReport(job.id(), blockers, refusing);
        }

        /** What one gate has to say about the job; nothing when it passes. */
        private List<String> blockers(Gate gate) {
            FlashSpec flash = board.flashSpec();
            Optional<String> found =
                    switch (gate) {
                        case LEASE ->
                            lease.isEmpty()
                                    ? Optional.of("nobody holds a lease on that board")
                                    : when(
                                            !lease.get().holder().equals(job.requestedBy()),
                                            "the board is leased to "
                                                    + lease.get().holder());
                        case STATE ->
                            when(
                                    state.state() != BoardStatus.LEASED && state.state() != BoardStatus.PROGRAMMING,
                                    "the board is " + state.state().value());
                        case DEVICE ->
                            when(
                                    !record.device().equals(board.device().device()),
                                    "the image is for " + record.device() + " and the board carries "
                                            + board.device().device());
                        case CONFIGURATION ->
                            when(
                                    !job.writesFlash()
                                            && record.length() > board.device().configBytes(),
                                    "the image is longer than this device's configuration");
                        case CHANNEL -> channelBlocker();
                        case PARTIAL -> when(record.isPartial(), "a partial image cannot be written to flash");
                        case CAPACITY ->
                            when(
                                    record.length() > flash.capacityBytes(),
                                    "the image is bigger than the flash on this board");
                        case TIMING ->
                            !requireTiming
                                    ? Optional.empty()
                                    : timingClosed.isEmpty()
                                            ? Optional.of("no timing report has been read for this image")
                                            : when(!timingClosed.get(), "the design did not close timing");
                        case WEAR ->
                            when(
                                    job.touchesGolden()
                                            && wear.isPresent()
                                            && wear.get().isWorn(board.tag(), flash),
                                    "the flash on this board is near the end of its erase life");
                        case BUS_WIDTH ->
                            when(
                                    options.effectiveRead().needsQuad() && !flash.quadCapable(),
                                    "the image reads its flash four bits wide and the " + flash.name()
                                            + " on this board cannot");
                        case JUMP -> jumpBlocker(FlashLayout.halves(flash));
                        case REACH -> reachBlocker(FlashLayout.halves(flash));
                        case CABLE ->
                            board.adapter().isEmpty()
                                    ? Optional.of("no cable is on that board")
                                    : when(
                                            !board.adapter().get().spec().canProgramFlash(),
                                            "the " + board.adapter().get().model()
                                                    + " on this board cannot write flash");
                    };
            return found.stream().toList();
        }

        private Optional<String> channelBlocker() {
            List<Channel> allowed = allowedChannels(job.mode());
            String channels = allowed.stream().map(Channel::value).collect(Collectors.joining(" or "));
            return when(
                    allowed.stream().noneMatch(channel -> channel.value().equals(record.channel())),
                    "a " + job.mode().value() + " write takes " + channels + " images and this one is in "
                            + record.channel());
        }

        /**
         * A golden image that jumps has to jump to the update image, and an update image must not
         * jump at all: it would restart configuration from wherever it points every time it loaded.
         */
        private Optional<String> jumpBlocker(FlashLayout layout) {
            if (options.jumpsTo().isEmpty()) {
                return Optional.empty();
            }
            long jump = options.jumpsTo().getAsLong();
            long update = layout.region("update").start();
            if (!job.touchesGolden()) {
                return Optional.of(String.format(
                        Locale.ROOT, "an update image that jumps to 0x%08x would never stay loaded", jump));
            }
            return when(
                    jump != update,
                    String.format(
                            Locale.ROOT,
                            "the golden image jumps to 0x%08x and the update image starts at 0x%08x",
                            jump,
                            update));
        }

        /** Only the stretch of the region the image covers has to be within the read's reach. */
        private Optional<String> reachBlocker(FlashLayout layout) {
            FlashRegion target = layout.region(job.mode().region().orElseThrow());
            long last = Math.min(target.start() + record.length(), target.end()) - 1;
            return when(
                    !options.effectiveRead().reaches(last),
                    "the " + target.name() + " region runs past 16 MiB and the image reads with three address bytes");
        }

        private static Optional<String> when(boolean refused, String reason) {
            return refused ? Optional.of(reason) : Optional.empty();
        }
    }
}
