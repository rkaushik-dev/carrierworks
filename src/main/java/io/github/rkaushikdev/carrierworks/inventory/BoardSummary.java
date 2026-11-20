package io.github.rkaushikdev.carrierworks.inventory;

import java.util.List;

/**
 * The short form of a board for a listing, built once rather than in the template.
 *
 * @param tag the asset tag
 * @param model the carrier model
 * @param device the device on it
 * @param location where it is racked
 * @param cards the cards fitted, in slot order
 */
public record BoardSummary(String tag, String model, String device, String location, List<String> cards) {

    public BoardSummary {
        cards = List.copyOf(cards);
    }

    /** The summary of one board. */
    public static BoardSummary of(Board board) {
        return new BoardSummary(
                board.tag(),
                board.model(),
                board.device().device(),
                board.location().toString(),
                board.fittedCards().stream().map(spec -> spec.name()).toList());
    }
}
