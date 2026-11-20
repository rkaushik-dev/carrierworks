package io.github.rkaushikdev.carrierworks;

import io.github.rkaushikdev.carrierworks.util.Repr;
import java.util.Comparator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One switched outlet on one distribution unit.
 *
 * <p>Power is a separate address space from {@link BenchLocation} on purpose. A board in rack 2
 * can perfectly well be fed from the distribution unit that lives in rack 3, because that is where
 * the free outlet was on the day somebody racked it.
 *
 * @param pdu the distribution unit, as a slug
 * @param outlet one to 24
 */
public record PduChannel(String pdu, int outlet) implements Comparable<PduChannel> {

    private static final Pattern PDU =
            Pattern.compile("(?<pdu>[a-z0-9][a-z0-9-]*)[/:](?:outlet-?)?(?<outlet>\\d{1,2})");

    private static final Comparator<PduChannel> ORDER =
            Comparator.comparing(PduChannel::pdu).thenComparingInt(PduChannel::outlet);

    public PduChannel {
        pdu = Ids.slug(pdu);
        if (outlet < 1 || outlet > 24) {
            throw new ValidationError("outlet out of range", "outlet", Details.of("given", outlet));
        }
    }

    /** Read a channel however it was typed: {@code pdu-a/outlet-7}, {@code pdu-a:7}. */
    public static PduChannel parse(String text) {
        String cleaned = text.strip().toLowerCase(Locale.ROOT).replace(" ", "");
        Matcher match = PDU.matcher(cleaned);
        if (!match.matches()) {
            throw new ValidationError("cannot read a pdu channel from " + Repr.of(text), "pdu");
        }
        return new PduChannel(match.group("pdu"), Integer.parseInt(match.group("outlet")));
    }

    @Override
    public int compareTo(PduChannel other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%s/outlet-%02d", pdu, outlet);
    }
}
