package io.github.rkaushikdev.carrierworks.services;

import java.util.ArrayList;
import java.util.List;

/**
 * What a bench file did to the database.
 *
 * @param added the boards put on the bench, in tag order
 * @param replaced the boards already there that were written over, in tag order
 * @param skipped the boards already there that were left alone, in tag order
 */
public record ImportOutcome(List<String> added, List<String> replaced, List<String> skipped) {

    public ImportOutcome {
        added = List.copyOf(added);
        replaced = List.copyOf(replaced);
        skipped = List.copyOf(skipped);
    }

    /** How many boards the file changed. */
    public int changed() {
        return added.size() + replaced.size();
    }

    /** {@code added bx-20, bx-21; left alone bx-14}, or {@code nothing in that file}. */
    public String describe() {
        List<String> parts = new ArrayList<>();
        if (!added.isEmpty()) {
            parts.add("added " + String.join(", ", added));
        }
        if (!replaced.isEmpty()) {
            parts.add("replaced " + String.join(", ", replaced));
        }
        if (!skipped.isEmpty()) {
            parts.add("left alone " + String.join(", ", skipped));
        }
        return parts.isEmpty() ? "nothing in that file" : String.join("; ", parts);
    }
}
