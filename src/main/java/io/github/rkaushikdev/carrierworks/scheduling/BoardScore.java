package io.github.rkaushikdev.carrierworks.scheduling;

import java.util.Comparator;

/**
 * Why one board came out ahead of another.
 *
 * <p>Scores order lowest first, which is best first: by fault penalty, then spare capabilities,
 * then spare logic, then the longest idle, and finally by tag, so every complete tie still has one
 * answer.
 *
 * @param board the board's asset tag
 * @param faultPenalty how many times the board has faulted recently, never below zero
 * @param surplusTokens the capabilities the board has that the job did not ask for
 * @param surplusLuts the board's logic, in thousands of luts
 * @param idleSeconds how long since the board was last used, in whole seconds, never below zero
 */
public record BoardScore(String board, int faultPenalty, int surplusTokens, int surplusLuts, long idleSeconds)
        implements Comparable<BoardScore> {

    private static final Comparator<BoardScore> ORDER = Comparator.comparingInt(BoardScore::faultPenalty)
            .thenComparingInt(BoardScore::surplusTokens)
            .thenComparingInt(BoardScore::surplusLuts)
            .thenComparing(BoardScore::idleSeconds, Comparator.reverseOrder())
            .thenComparing(BoardScore::board);

    /** Negative when this board is the better choice. */
    @Override
    public int compareTo(BoardScore other) {
        return ORDER.compare(this, other);
    }

    /** {@code bx-14: 3 spare capabilities, 203k spare luts, idle 300s, fault penalty 1}. */
    public String describe() {
        return board + ": " + surplusTokens + " spare capabilities, " + surplusLuts + "k spare luts, idle "
                + idleSeconds + "s, fault penalty " + faultPenalty;
    }
}
