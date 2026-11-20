package io.github.rkaushikdev.carrierworks.scheduling;

import io.github.rkaushikdev.carrierworks.CapabilityProfile;
import io.github.rkaushikdev.carrierworks.Requirement;
import io.github.rkaushikdev.carrierworks.RequirementSet;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardProfile;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Choosing between the boards that would all do.
 *
 * <p>Any board that meets the requirements can run the job, so the choice is about what it costs
 * the lab rather than about the job. Four things decide it, in this order, and the order is the
 * policy.
 *
 * <p>A board that has been faulting recently goes last. It might be fine. It might also fail this
 * job and cost the person an hour, and there are usually other boards.
 *
 * <p>Then the smallest board that fits. Putting a job that needs an Artix on the one Kintex with the
 * converter card is how the person who actually needs that card finds nothing free. Surplus is
 * counted in capabilities first and in device size second, because a spare card is scarcer than
 * spare logic.
 *
 * <p>Then the board that has been idle longest, which spreads wear and, more usefully, keeps the
 * boards nobody has touched for a month in circulation where their faults get found.
 *
 * <p>The board name breaks the tie, so the same inputs always give the same answer and a scheduling
 * decision can be reproduced from a log.
 */
public final class BoardMatcher {

    // Logic surplus is counted in thousands of luts, because a difference of a few hundred between
    // two carriers is not a reason to prefer either.
    private static final int LUT_BUCKET = 1000;

    // Every board has a JTAG port, so having one is never surplus.
    private static final String UNIVERSAL_TOKEN = "jtag";

    private BoardMatcher() {}

    /** {@link #score(Board, RequirementSet, Instant, Optional, int)} for a board never used and never faulted. */
    public static BoardScore score(Board board, RequirementSet wanted, Instant now) {
        return score(board, wanted, now, Optional.empty(), 0);
    }

    /**
     * What this board would cost the lab, lower being better.
     *
     * <p>A board never used counts as no idle time, and a last use in the future counts as none
     * either. A negative fault count is treated as no faults.
     */
    public static BoardScore score(
            Board board, RequirementSet wanted, Instant now, Optional<Instant> lastUsed, int recentFaults) {
        CapabilityProfile profile = BoardProfile.of(board);
        Set<String> asked = askedFor(wanted);
        int surplusTokens = (int) profile.tokens().stream()
                .filter(token -> !asked.contains(token) && !token.equals(UNIVERSAL_TOKEN))
                .count();
        int surplusLuts = profile.number("luts") / LUT_BUCKET;
        long idle = lastUsed.map(when -> Math.max(Times.seconds(Duration.between(when, now)), 0L))
                .orElse(0L);
        return new BoardScore(board.tag(), Math.max(recentFaults, 0), surplusTokens, surplusLuts, idle);
    }

    /** {@link #rank(List, RequirementSet, Instant, Map, Map)} with no history of use or faults. */
    public static List<BoardScore> rank(List<Board> boards, RequirementSet wanted, Instant now) {
        return rank(boards, wanted, now, Map.of(), Map.of());
    }

    /**
     * Every board that matches, best first.
     *
     * <p>Boards that do not match are left out rather than scored badly. A board without the card
     * the job needs is not a worse choice, it is not a choice.
     *
     * @param lastUsed when each board was last used, by tag; a board missing here was never used
     * @param faults how many recent faults each board has had, by tag; a board missing here has none
     */
    public static List<BoardScore> rank(
            List<Board> boards,
            RequirementSet wanted,
            Instant now,
            Map<String, Instant> lastUsed,
            Map<String, Integer> faults) {
        return boards.stream()
                .filter(board -> BoardProfile.matches(board, wanted))
                .map(board -> score(
                        board,
                        wanted,
                        now,
                        Optional.ofNullable(lastUsed.get(board.tag())),
                        faults.getOrDefault(board.tag(), 0)))
                .sorted()
                .toList();
    }

    /** {@link #choose(List, RequirementSet, Instant, Map, Map)} with no history of use or faults. */
    public static Optional<Board> choose(List<Board> boards, RequirementSet wanted, Instant now) {
        return choose(boards, wanted, now, Map.of(), Map.of());
    }

    /** The board to use, or nothing when none of them will do. */
    public static Optional<Board> choose(
            List<Board> boards,
            RequirementSet wanted,
            Instant now,
            Map<String, Instant> lastUsed,
            Map<String, Integer> faults) {
        return rank(boards, wanted, now, lastUsed, faults).stream().findFirst().flatMap(best -> boards.stream()
                .filter(board -> board.tag().equals(best.board()))
                .findFirst());
    }

    private static Set<String> askedFor(RequirementSet wanted) {
        return wanted.requirements().stream()
                .filter(requirement -> requirement.kind() == Requirement.Kind.PRESENT)
                .map(Requirement::key)
                .collect(Collectors.toSet());
    }
}
