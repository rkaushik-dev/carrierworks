package io.github.rkaushikdev.carrierworks.jobs;

import io.github.rkaushikdev.carrierworks.artifacts.ArtifactRecord;
import io.github.rkaushikdev.carrierworks.artifacts.Channel;
import io.github.rkaushikdev.carrierworks.bitstream.ConfigOptions;
import io.github.rkaushikdev.carrierworks.bitstream.SpiRead;
import io.github.rkaushikdev.carrierworks.catalog.DeviceSpec;
import io.github.rkaushikdev.carrierworks.catalog.FlashSpec;
import io.github.rkaushikdev.carrierworks.flash.FlashLayout;
import io.github.rkaushikdev.carrierworks.flash.FlashRegion;
import io.github.rkaushikdev.carrierworks.flash.WearLedger;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardState;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.jtag.AdapterUnit;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import java.util.ArrayList;
import java.util.List;
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
 * </ul>
 *
 * <p>The blockers are sentences operators read, and {@link GateReport#describe()} puts them in one
 * line.
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

        /** Run every check and report all of them at once. */
        public GateReport report() {
            List<String> blockers = new ArrayList<>();
            blockers.addAll(leaseBlockers());
            blockers.addAll(deviceBlockers());
            List<Channel> allowed = allowedChannels(job.mode());
            if (allowed.stream().noneMatch(channel -> channel.value().equals(record.channel()))) {
                String channels = allowed.stream().map(Channel::value).collect(Collectors.joining(" or "));
                blockers.add("a " + job.mode().value() + " write takes " + channels + " images and this one is in "
                        + record.channel());
            }
            if (job.writesFlash()) {
                blockers.addAll(flashBlockers());
            }
            return new GateReport(job.id(), blockers);
        }

        private List<String> deviceBlockers() {
            List<String> blockers = new ArrayList<>();
            DeviceSpec fitted = board.device();
            if (!record.device().equals(fitted.device())) {
                blockers.add("the image is for " + record.device() + " and the board carries " + fitted.device());
            }
            if (!job.writesFlash() && record.length() > fitted.configBytes()) {
                blockers.add("the image is longer than this device's configuration");
            }
            return blockers;
        }

        /** What stands between this image and the configuration flash on this board. */
        private List<String> flashBlockers() {
            List<String> blockers = new ArrayList<>();
            FlashSpec flash = board.flashSpec();
            if (record.isPartial()) {
                blockers.add("a partial image cannot be written to flash");
            }
            if (record.length() > flash.capacityBytes()) {
                blockers.add("the image is bigger than the flash on this board");
            }
            if (requireTiming && timingClosed.isEmpty()) {
                blockers.add("no timing report has been read for this image");
            } else if (requireTiming && !timingClosed.get()) {
                blockers.add("the design did not close timing");
            }
            if (job.touchesGolden() && wear.isPresent() && wear.get().isWorn(board.tag(), flash)) {
                blockers.add("the flash on this board is near the end of its erase life");
            }
            SpiRead read = options.effectiveRead();
            if (read.needsQuad() && !flash.quadCapable()) {
                blockers.add(
                        "the image reads its flash four bits wide and the " + flash.name() + " on this board cannot");
            }
            FlashRegion target =
                    FlashLayout.halves(flash).region(job.mode().region().orElseThrow());
            if (!read.reaches(Math.min(target.start() + record.length(), target.end()) - 1)) {
                blockers.add("the " + target.name() + " region runs past 16 MiB and the image reads with three"
                        + " address bytes");
            }
            Optional<AdapterUnit> adapter = board.adapter();
            if (adapter.isEmpty()) {
                blockers.add("no cable is on that board");
            } else if (!adapter.get().spec().canProgramFlash()) {
                blockers.add("the " + adapter.get().model() + " on this board cannot write flash");
            }
            return blockers;
        }

        private List<String> leaseBlockers() {
            List<String> blockers = new ArrayList<>();
            if (lease.isEmpty()) {
                blockers.add("nobody holds a lease on that board");
            } else if (!lease.get().holder().equals(job.requestedBy())) {
                blockers.add("the board is leased to " + lease.get().holder());
            }
            if (state.state() != BoardStatus.LEASED && state.state() != BoardStatus.PROGRAMMING) {
                blockers.add("the board is " + state.state().value());
            }
            return blockers;
        }
    }
}
