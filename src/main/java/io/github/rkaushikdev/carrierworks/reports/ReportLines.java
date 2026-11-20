package io.github.rkaushikdev.carrierworks.reports;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The header line both build reports open with, naming the design they describe. */
final class ReportLines {

    // The colon is required: the timing report's own heading is "Design Timing Summary" and
    // matching that gives every design the name timing.
    private static final Pattern DESIGN =
            Pattern.compile("\\s*\\|?\\s*Design\\s*:\\s*(?<design>[\\w\\-/.]+)", Pattern.CASE_INSENSITIVE);

    private ReportLines() {}

    /** The design a line names, when it starts {@code Design : name}. */
    static Optional<String> design(String line) {
        Matcher found = DESIGN.matcher(line);
        return found.lookingAt() ? Optional.of(found.group("design")) : Optional.empty();
    }
}
