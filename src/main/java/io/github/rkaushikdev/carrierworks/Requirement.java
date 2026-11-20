package io.github.rkaushikdev.carrierworks;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One thing a job asks of a board.
 *
 * <p>Requirements are written as short strings because they come from three places that all have
 * to write the same thing: a command line, a file in a regression repo, and a text column in the
 * database. The grammar is small on purpose. A bare token asks for a capability, a token with a
 * bang in front asks for its absence, a comparison asks about a number, and an equals asks about an
 * attribute.
 *
 * <p>The explanations matter as much as the answers. A scheduler that says no without saying why
 * sends people to look at the rack, and the walk to the rack is five minutes each way.
 *
 * @param text the requirement as written, trimmed and lower cased
 * @param kind which of the four questions it asks
 * @param key the capability, count or attribute it asks about
 * @param op the comparison, empty for a token
 * @param value what it compares against, empty for a token
 */
public record Requirement(String text, Kind kind, String key, String op, String value) {

    /** The four questions a requirement can ask. */
    public enum Kind {
        /** The board has this capability. */
        PRESENT,
        /** The board does not have this capability. */
        ABSENT,
        /** A count compares a certain way to a number. */
        NUMBER,
        /** A named fact equals, or does not equal, a value. */
        ATTRIBUTE
    }

    private static final Pattern COMPARISON =
            Pattern.compile("(?<key>[a-z][a-z0-9_-]*)\\s*(?<op>>=|<=|!=|=|>|<)\\s*(?<value>\\S+)");
    private static final Pattern WHOLE = Pattern.compile("-?\\d+");

    /** Read one requirement as somebody wrote it. */
    public static Requirement parse(String text) {
        String cleaned = text.strip().toLowerCase(Locale.ROOT);
        if (cleaned.isEmpty()) {
            throw new ValidationError("empty requirement", "requirement");
        }
        if (cleaned.startsWith("!")) {
            return new Requirement(cleaned, Kind.ABSENT, Ids.slug(cleaned.substring(1)), "", "");
        }
        Matcher match = COMPARISON.matcher(cleaned);
        if (!match.matches()) {
            return new Requirement(cleaned, Kind.PRESENT, Ids.slug(cleaned), "", "");
        }
        String key = Ids.slug(match.group("key"));
        String op = match.group("op");
        String raw = match.group("value");
        if (op.equals(">=") || op.equals("<=") || op.equals(">") || op.equals("<")) {
            if (!WHOLE.matcher(raw).matches()) {
                throw new ValidationError(
                        "a comparison needs a whole number", "requirement", Details.of("given", text));
            }
            return new Requirement(cleaned, Kind.NUMBER, key, op, raw);
        }
        return new Requirement(cleaned, Kind.ATTRIBUTE, key, op, raw);
    }

    /** Whether a board with this profile meets the requirement. */
    public boolean satisfiedBy(CapabilityProfile profile) {
        return explain(profile).isEmpty();
    }

    /** Nothing when it is met, otherwise why it is not. */
    public Optional<String> explain(CapabilityProfile profile) {
        return switch (kind) {
            case PRESENT -> profile.tokens().contains(key) ? Optional.empty() : Optional.of("no " + key);
            case ABSENT ->
                profile.tokens().contains(key)
                        ? Optional.of("has " + key + " and the job asked for a board without it")
                        : Optional.empty();
            case NUMBER -> {
                int have = profile.number(key);
                long want = Long.parseLong(value);
                yield compare(have, want)
                        ? Optional.empty()
                        : Optional.of(key + " is " + have + ", wanted " + op + want);
            }
            case ATTRIBUTE -> attribute(profile.attribute(key));
        };
    }

    private Optional<String> attribute(Optional<String> have) {
        if (op.equals("=")) {
            if (have.isPresent() && have.get().equals(value)) {
                return Optional.empty();
            }
            return Optional.of(key + " is " + have.orElse("nothing") + ", wanted " + value);
        }
        if (have.isEmpty() || !have.get().equals(value)) {
            return Optional.empty();
        }
        return Optional.of(key + " is " + value + " and the job asked for anything else");
    }

    private boolean compare(long have, long want) {
        return switch (op) {
            case ">=" -> have >= want;
            case "<=" -> have <= want;
            case ">" -> have > want;
            default -> have < want;
        };
    }

    @Override
    public String toString() {
        return text;
    }
}
