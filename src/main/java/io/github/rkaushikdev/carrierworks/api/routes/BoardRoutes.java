package io.github.rkaushikdev.carrierworks.api.routes;

import io.github.rkaushikdev.carrierworks.PduChannel;
import io.github.rkaushikdev.carrierworks.Role;
import io.github.rkaushikdev.carrierworks.api.BoardIn;
import io.github.rkaushikdev.carrierworks.api.BoardOut;
import io.github.rkaushikdev.carrierworks.api.Page;
import io.github.rkaushikdev.carrierworks.api.Requires;
import io.github.rkaushikdev.carrierworks.api.SlotIn;
import io.github.rkaushikdev.carrierworks.api.StateIn;
import io.github.rkaushikdev.carrierworks.api.Work;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardSlot;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.jtag.AdapterUnit;
import io.github.rkaushikdev.carrierworks.services.Inventory;
import io.github.rkaushikdev.carrierworks.util.Times;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The board routes: what is on the bench and what state it is in. */
@RestController
@RequestMapping("/boards")
public class BoardRoutes {

    private static final String FAULTED = BoardStatus.FAULTED.value();
    private static final String HAND_FAULT = "faulted by hand";
    private static final String ACTOR = "api";

    private final Work work;
    private final Clock clock;

    public BoardRoutes(Work work, Clock clock) {
        this.work = work;
        this.clock = clock;
    }

    /** Every board, or the ones a filter asks for: only the free ones, or one rack. */
    @GetMapping
    @Requires(Role.READER)
    public List<BoardOut> listBoards(
            @RequestParam(defaultValue = "false") boolean available, @RequestParam Optional<String> rack, Page window) {
        return work.apply(unit -> {
            List<Board> boards =
                    rack.isPresent() ? unit.boards().inRack(rack.get()) : Inventory.listBoards(unit, available);
            return window.slice(boards).stream()
                    .map(board -> BoardOut.of(board, unit.boards().state(board.tag())))
                    .toList();
        });
    }

    /** One board, with the state it is in. */
    @GetMapping("/{tag}")
    @Requires(Role.READER)
    public BoardOut readBoard(@PathVariable String tag) {
        return work.apply(
                unit -> BoardOut.of(unit.boards().get(tag), unit.boards().state(tag)));
    }

    /** Put a board on the bench, available. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Requires(Role.ADMIN)
    public BoardOut addBoard(@Valid @RequestBody BoardIn incoming) {
        Instant moment = Times.now(clock);
        return work.apply(unit -> {
            Board board = Inventory.addBoard(unit, toBoard(incoming), moment);
            unit.flush();
            return BoardOut.of(board, unit.boards().state(board.tag()));
        });
    }

    /**
     * Move a board's state, through the rules that say which moves exist.
     *
     * <p>Faulting goes its own way, because a faulted board gives back whatever lease is on it.
     */
    @PostMapping("/{tag}/state")
    @Requires(Role.OPERATOR)
    public BoardOut setState(@PathVariable String tag, @Valid @RequestBody StateIn change) {
        Instant moment = Times.now(clock);
        String actor = change.actor().isEmpty() ? ACTOR : change.actor();
        return work.apply(unit -> {
            if (change.state().equals(FAULTED)) {
                String reason = change.reason().isEmpty() ? HAND_FAULT : change.reason();
                Inventory.faultBoard(unit, tag, moment, reason, actor);
            } else {
                Inventory.moveState(unit, tag, BoardStatus.parse(change.state()), moment, change.reason(), actor);
            }
            return BoardOut.of(unit.boards().get(tag), unit.boards().state(tag));
        });
    }

    /** Record a card being fitted to a slot, or taken out of one when no card is named. */
    @PutMapping("/{tag}/slots/{slot}")
    @Requires(Role.ADMIN)
    public BoardOut fitCard(@PathVariable String tag, @PathVariable String slot, @RequestParam Optional<String> card) {
        return work.apply(unit -> {
            Board board = Inventory.fitCard(unit, tag, slot, card);
            unit.flush();
            return BoardOut.of(board, unit.boards().state(tag));
        });
    }

    /** Take a board off the bench entirely. */
    @DeleteMapping("/{tag}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Requires(Role.ADMIN)
    public void removeBoard(@PathVariable String tag) {
        work.run(unit -> unit.boards().remove(tag));
    }

    /** One board out of the shape the wire uses. A cable is only recorded when both halves of it are. */
    private static Board toBoard(BoardIn incoming) {
        Optional<String> serial = incoming.adapterSerial().filter(text -> !text.isEmpty());
        Optional<String> model = incoming.adapterModel().filter(text -> !text.isEmpty());
        Optional<AdapterUnit> adapter = serial.isPresent() && model.isPresent()
                ? Optional.of(new AdapterUnit(serial.get(), model.get()))
                : Optional.empty();
        Board.Builder board = Board.builder(
                        incoming.asset(), incoming.model(), incoming.part(), incoming.flash(), incoming.location())
                .revision(incoming.revision())
                .slots(incoming.slots().stream().map(BoardRoutes::toSlot).toList());
        adapter.ifPresent(board::adapter);
        incoming.pdu().map(PduChannel::parse).ifPresent(board::pdu);
        return board.vadjMillivolts(incoming.vadjMillivolts())
                .headerVoltageMv(incoming.headerVoltageMv())
                .maxTckHz(incoming.maxTckHz())
                .notes(incoming.notes())
                .build();
    }

    private static BoardSlot toSlot(SlotIn slot) {
        return new BoardSlot(slot.name(), slot.connector(), slot.fitted());
    }
}
