package io.github.rkaushikdev.carrierworks.api.routes;

import io.github.rkaushikdev.carrierworks.AuditEntry;
import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.Role;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.api.Page;
import io.github.rkaushikdev.carrierworks.api.Requires;
import io.github.rkaushikdev.carrierworks.api.Work;
import io.github.rkaushikdev.carrierworks.persistence.AuditRepository;
import io.github.rkaushikdev.carrierworks.services.Audit;
import io.github.rkaushikdev.carrierworks.util.Times;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The audit routes: who did what, most recent first. */
@RestController
@RequestMapping("/audit")
public class AuditRoutes {

    private final Work work;

    public AuditRoutes(Work work) {
        this.work = work;
    }

    /**
     * One line of the trail on the way out.
     *
     * @param at when it happened
     * @param actor who did it
     * @param action what kind of thing they did
     * @param subject what it was done to, or nothing
     * @param detail anything they said about it, or nothing
     */
    public record EntryOut(Instant at, String actor, String action, String subject, String detail) {

        /** One line. */
        public static EntryOut of(AuditEntry entry) {
            return new EntryOut(entry.at(), entry.actor(), entry.action(), entry.subject(), entry.detail());
        }
    }

    /**
     * The trail, newest first, narrowed by whatever was asked for.
     *
     * @param subject one board, image or lease
     * @param actor one person or script
     * @param action one kind of thing done
     * @param since nothing older than this moment, written with its offset
     * @param limit how many lines at most
     */
    @GetMapping
    @Requires(Role.READER)
    public List<EntryOut> readTrail(
            @RequestParam Optional<String> subject,
            @RequestParam Optional<String> actor,
            @RequestParam Optional<String> action,
            @RequestParam Optional<String> since,
            @RequestParam(defaultValue = "" + Audit.DEFAULT_LIMIT) @Min(1) @Max(Page.MAX) int limit) {
        Optional<Instant> from = since.map(AuditRoutes::moment);
        return work.apply(unit -> {
            List<AuditEntry> found;
            if (subject.isEmpty() && actor.isEmpty() && action.isEmpty() && from.isEmpty()) {
                found = Audit.recent(unit, limit);
            } else {
                AuditRepository.Search search =
                        AuditRepository.Search.everything().limit(limit);
                search = subject.map(search::subject).orElse(search);
                search = actor.map(search::actor).orElse(search);
                search = action.map(search::action).orElse(search);
                search = from.map(search::since).orElse(search);
                found = Audit.search(unit, search);
            }
            return found.stream().map(EntryOut::of).toList();
        });
    }

    private static Instant moment(String text) {
        try {
            return Times.parse(text);
        } catch (DateTimeParseException failure) {
            throw new ValidationError("that is not a moment with an offset", "since", Details.of("given", text));
        }
    }
}
