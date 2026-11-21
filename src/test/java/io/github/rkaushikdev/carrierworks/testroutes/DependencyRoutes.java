package io.github.rkaushikdev.carrierworks.testroutes;

import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.api.Work;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.inventory.BoardState;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.time.Clock;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Routes that show what a route is handed: the clock, a unit of work and the settings. */
@RestController
public class DependencyRoutes {

    private final Work work;
    private final Clock clock;
    private final BenchSettings settings;

    public DependencyRoutes(Work work, Clock clock, BenchSettings settings) {
        this.work = work;
        this.clock = clock;
        this.settings = settings;
    }

    @GetMapping("/when")
    public Map<String, Object> when() {
        return Map.of("now", Times.iso(Times.now(clock)));
    }

    @PostMapping("/work/boards")
    public Map<String, Object> add() {
        work.run(unit -> unit.boards()
                .add(BoardSetup.kintex("bx-14").build(), new BoardState(BoardStatus.AVAILABLE, Times.now(clock))));
        return Map.of("added", true);
    }

    @GetMapping("/work/boards")
    public Map<String, Object> listing() {
        return Map.of(
                "boards",
                work.apply(unit -> unit.boards().all().stream().map(Board::tag).toList()));
    }

    @GetMapping("/site")
    public Map<String, Object> site() {
        return Map.of("site", settings.site());
    }
}
