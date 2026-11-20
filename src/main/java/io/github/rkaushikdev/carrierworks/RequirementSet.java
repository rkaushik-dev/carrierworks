package io.github.rkaushikdev.carrierworks;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Everything a job asks for, all of which has to hold at once.
 *
 * <p>The one time somebody wanted a boolean or, the honest answer was to book two boards.
 *
 * @param requirements the requirements, in the order they were written
 */
public record RequirementSet(List<Requirement> requirements) implements Iterable<Requirement> {

    private static final Pattern SEPARATORS = Pattern.compile("[,\n]");

    public RequirementSet {
        requirements = List.copyOf(requirements);
    }

    /** A job that asks for nothing. */
    public static RequirementSet none() {
        return new RequirementSet(List.of());
    }

    /** Requirements written as one string, separated by commas or newlines; blank parts skipped. */
    public static RequirementSet parse(String texts) {
        return new RequirementSet(SEPARATORS
                .splitAsStream(texts)
                .filter(part -> !part.isBlank())
                .map(Requirement::parse)
                .toList());
    }

    /** Requirements written one to an entry; every entry has to be one. */
    public static RequirementSet parse(Collection<String> texts) {
        return new RequirementSet(texts.stream().map(Requirement::parse).toList());
    }

    /** The requirements a board with this profile does not meet, in order. */
    public List<Requirement> unmet(CapabilityProfile profile) {
        return requirements.stream().filter(req -> !req.satisfiedBy(profile)).toList();
    }

    /** Whether a board with this profile meets every requirement. */
    public boolean satisfiedBy(CapabilityProfile profile) {
        return unmet(profile).isEmpty();
    }

    /** One line an operator can read, listing every unmet requirement. */
    public String whyNot(CapabilityProfile profile) {
        return unmet(profile).stream()
                .map(req -> req.explain(profile))
                .flatMap(Optional::stream)
                .filter(reason -> !reason.isEmpty())
                .collect(Collectors.joining("; "));
    }

    /** How many requirements there are. */
    public int size() {
        return requirements.size();
    }

    @Override
    public Iterator<Requirement> iterator() {
        return requirements.iterator();
    }
}
