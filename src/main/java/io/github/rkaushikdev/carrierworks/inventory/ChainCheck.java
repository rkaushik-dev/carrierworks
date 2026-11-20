package io.github.rkaushikdev.carrierworks.inventory;

import io.github.rkaushikdev.carrierworks.jtag.ChainDifference;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * What a scan of one board's chain came to.
 *
 * <p>A scan that does not match is not one problem, it is three, and this says which: a board with
 * the wrong part fitted goes to maintenance, a chain that does not answer at all is usually a cable
 * and gets retried, and a chain in the wrong order means somebody has plugged into the wrong
 * header.
 *
 * @param board the board's tag
 * @param expected the chain the board should present, as its log line
 * @param wiring the wiring fault the scan showed, if it showed one
 * @param differences every way the chain that answered differs from the expected one
 * @param found what answered, nearest TDI first, or empty text when the scan was a wiring fault
 */
public record ChainCheck(
        String board, String expected, Optional<String> wiring, List<ChainDifference> differences, String found) {

    private static final String FAULT_KIND = "chain";

    public ChainCheck {
        differences = List.copyOf(differences);
    }

    /** A check that stopped at a wiring fault, before any device was looked at. */
    public static ChainCheck wiringFault(String board, String expected, String wiring) {
        return new ChainCheck(board, expected, Optional.of(wiring), List.of(), "");
    }

    /** Whether the board answered with exactly the chain it should have. */
    public boolean ok() {
        return wiring.isEmpty() && differences.isEmpty();
    }

    /** Which kind of fault to record, or empty when the board is fine. */
    public Optional<String> faultKind() {
        return ok() ? Optional.empty() : Optional.of(FAULT_KIND);
    }

    /** A chain that did not answer is worth another go; a wrong one is not. */
    public boolean retryWorthwhile() {
        return wiring.isPresent();
    }

    /** Where the board should go, or empty to leave it where it is. */
    public Optional<BoardStatus> nextState() {
        if (ok()) {
            return Optional.empty();
        }
        return Optional.of(wiring.isPresent() ? BoardStatus.FAULTED : BoardStatus.MAINTENANCE);
    }

    /**
     * One line for a log, starting with the board's tag: that the chain matches, the wiring fault,
     * or every difference in turn.
     */
    public String describe() {
        if (ok()) {
            return board + " chain matches " + expected;
        }
        if (wiring.isPresent()) {
            return board + " " + wiring.get();
        }
        String reasons = differences.stream().map(ChainDifference::describe).collect(Collectors.joining("; "));
        return board + " chain does not match: " + reasons;
    }
}
