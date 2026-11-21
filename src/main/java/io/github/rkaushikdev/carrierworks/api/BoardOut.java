package io.github.rkaushikdev.carrierworks.api;

import io.github.rkaushikdev.carrierworks.PduChannel;
import io.github.rkaushikdev.carrierworks.catalog.CardSpec;
import io.github.rkaushikdev.carrierworks.catalog.DeviceSpec;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardState;
import io.github.rkaushikdev.carrierworks.jtag.AdapterUnit;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A board on the way out, with what a dashboard needs to draw it.
 *
 * @param asset the asset tag
 * @param model the carrier model
 * @param revision the carrier revision
 * @param part the device's ordering code
 * @param device the device on it, such as {@code xc7k325t}
 * @param family the device's family, such as {@code kintex7}
 * @param flash the configuration flash part
 * @param location where it is racked
 * @param cards the cards fitted, in slot order
 * @param transceiversFree the transceivers the fitted cards leave free
 * @param adapter the serial of the cable hanging off it, or null when it has none
 * @param pdu the outlet feeding it, or null when none is on record
 * @param state the state it is in, or {@value #UNKNOWN} when none is on record
 * @param stateSince when it entered that state, or null when that is not known
 * @param stateReason why it is in that state, or nothing
 */
public record BoardOut(
        String asset,
        String model,
        String revision,
        String part,
        String device,
        String family,
        String flash,
        String location,
        List<String> cards,
        int transceiversFree,
        Optional<String> adapter,
        Optional<String> pdu,
        String state,
        Optional<Instant> stateSince,
        String stateReason) {

    /** What a board with no state on record says it is in, rather than guessing. */
    public static final String UNKNOWN = "unknown";

    public BoardOut {
        cards = List.copyOf(cards);
    }

    /** A board whose state is not known. */
    public static BoardOut of(Board board) {
        return of(board, Optional.empty());
    }

    /** A board in the state given. */
    public static BoardOut of(Board board, BoardState state) {
        return of(board, Optional.of(state));
    }

    private static BoardOut of(Board board, Optional<BoardState> state) {
        DeviceSpec device = board.device();
        return new BoardOut(
                board.tag(),
                board.model(),
                board.revision(),
                board.part().orderingCode(),
                device.device(),
                device.family(),
                board.flash(),
                board.location().toString(),
                board.fittedCards().stream().map(CardSpec::name).toList(),
                board.transceiversFree(),
                board.adapter().map(AdapterUnit::serial),
                board.pdu().map(PduChannel::toString),
                state.map(known -> known.state().value()).orElse(UNKNOWN),
                state.map(BoardState::since),
                state.map(BoardState::reason).orElse(""));
    }
}
