package io.github.rkaushikdev.carrierworks.flash;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.catalog.FlashSpec;
import java.util.ArrayList;
import java.util.List;

/**
 * Turning "put this image there" into the steps that actually do it: everything to do, in order,
 * with what it is expected to cost.
 *
 * <p>A plan is built before anything is touched, and it is worth having as an object for two
 * reasons. It can be shown to somebody before it runs, which matters when the thing about to be
 * erased is the only working image on a board. And it carries a time estimate, which is the
 * difference between an operator waiting and an operator pulling the cable at the four minute mark
 * because nothing has moved.
 *
 * <p>The estimates come out of the datasheet typicals and they are honest about being typicals. A
 * part near the end of its life erases slower, and the plan does not pretend to know that.
 *
 * <p>The order is fixed and it is not negotiable: erase every sector the write will touch, then
 * program page by page, then read it all back if verification is on. Programming into a sector that
 * was not erased writes ones into zeros and only zeros into ones, so the result is neither the old
 * image nor the new one.
 *
 * @param part the flash part the plan is for
 * @param region the name of the region it writes or clears
 * @param steps everything to do, in order
 * @param imageBytes how many bytes of image it programs; none for an erase
 */
public record FlashPlan(FlashSpec part, String region, List<FlashStep> steps, long imageBytes) {

    private static final int READ_CHUNK = 65536;
    // A read back runs at roughly this rate over the adapters the lab has.
    private static final int READ_BYTES_PER_MS = 1200;

    public FlashPlan {
        steps = List.copyOf(steps);
    }

    /** The steps to put an image into a region, erasing in sectors and reading it back after. */
    public static FlashPlan forWrite(FlashLayout layout, String region, byte[] data) {
        return forWrite(layout, region, data, EraseUnit.SECTOR, true);
    }

    /**
     * The steps to put an image into a region of a part.
     *
     * @param erase the size of block to erase in
     * @param verify whether to read the image back once it is written
     * @throws ValidationError when there is nothing to program or it does not fit the region
     */
    public static FlashPlan forWrite(FlashLayout layout, String region, byte[] data, EraseUnit erase, boolean verify) {
        FlashRegion target = layout.region(region);
        if (data.length == 0) {
            throw new ValidationError("nothing to program", "data");
        }
        if (!target.holds(data.length)) {
            throw new ValidationError(
                    "the image does not fit that region",
                    "data",
                    Details.of("region", target.name(), "image", data.length, "room", target.length()));
        }
        List<FlashStep> steps =
                new ArrayList<>(erase.cover(layout.part(), target.start(), target.start() + data.length));
        steps.addAll(programSteps(layout.part(), target.start(), data.length));
        if (verify) {
            steps.addAll(verifySteps(target.start(), data.length));
        }
        return new FlashPlan(layout.part(), target.name(), steps, data.length);
    }

    /** The steps to clear a whole region in sectors and leave it erased. */
    public static FlashPlan forErase(FlashLayout layout, String region) {
        return forErase(layout, region, EraseUnit.SECTOR);
    }

    /** The steps to clear a whole region and leave it erased, in blocks of the size asked for. */
    public static FlashPlan forErase(FlashLayout layout, String region, EraseUnit erase) {
        FlashRegion target = layout.region(region);
        return new FlashPlan(layout.part(), target.name(), erase.cover(layout.part(), target.start(), target.end()), 0);
    }

    /** What the whole plan is expected to take, in milliseconds. */
    public long totalMs() {
        return steps.stream().mapToLong(FlashStep::estimatedMs).sum();
    }

    /** What the whole plan is expected to take, in whole seconds rounded up. */
    public long totalSeconds() {
        return Math.ceilDiv(totalMs(), 1000);
    }

    /** Every step of one kind, in order. */
    public List<FlashStep> stepsOf(StepKind kind) {
        return steps.stream().filter(step -> step.kind() == kind).toList();
    }

    /** How many bytes the erases clear between them. */
    public long erasedBytes() {
        return stepsOf(StepKind.ERASE).stream().mapToLong(FlashStep::length).sum();
    }

    /** How many bytes the page writes program between them. */
    public long programmedBytes() {
        return stepsOf(StepKind.PROGRAM).stream().mapToLong(FlashStep::length).sum();
    }

    /** Whether the plan reads the image back. */
    public boolean verifies() {
        return !stepsOf(StepKind.VERIFY).isEmpty();
    }

    /** {@code golden on n25q128a13: 2 erases, 512 page writes, 2 reads, about 3s}. */
    public String describe() {
        return region + " on " + part.name() + ": " + stepsOf(StepKind.ERASE).size() + " erases, "
                + stepsOf(StepKind.PROGRAM).size() + " page writes, "
                + stepsOf(StepKind.VERIFY).size()
                + " reads, about " + totalSeconds() + "s";
    }

    /**
     * Page writes, split at page boundaries rather than every page's worth of bytes.
     *
     * <p>A write that crosses a page boundary wraps to the start of the same page inside the part,
     * which corrupts silently, so the first write is short whenever the image does not begin on a
     * page boundary.
     */
    private static List<FlashStep> programSteps(FlashSpec part, long start, long length) {
        long cost = Math.max(1, part.pageProgramUs() / 1000);
        List<FlashStep> steps = new ArrayList<>();
        long offset = 0;
        while (offset < length) {
            long address = start + offset;
            long take = Math.min(part.pageBytes() - Math.floorMod(address, part.pageBytes()), length - offset);
            steps.add(new FlashStep(StepKind.PROGRAM, address, take, cost));
            offset += take;
        }
        return steps;
    }

    private static List<FlashStep> verifySteps(long start, long length) {
        List<FlashStep> steps = new ArrayList<>();
        long offset = 0;
        while (offset < length) {
            long take = Math.min(READ_CHUNK, length - offset);
            steps.add(new FlashStep(StepKind.VERIFY, start + offset, take, Math.max(1, take / READ_BYTES_PER_MS)));
            offset += take;
        }
        return steps;
    }
}
