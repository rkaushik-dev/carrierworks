package io.github.rkaushikdev.carrierworks;

import io.github.rkaushikdev.carrierworks.util.Repr;
import java.util.Comparator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The label stuck on the board, taken apart so it sorts properly.
 *
 * <p>Boards get an asset tag off a printed sheet, which is the only identifier that survives a
 * board being reflowed and coming back with a new serial. Tags print on labels in a rack and
 * people read them in order, so {@code bx-9} sorts before {@code bx-10} rather than after it,
 * which is what happens if anyone sorts the strings.
 *
 * @param prefix two to four lower case letters
 * @param number one to 99999
 */
public record AssetTag(String prefix, int number) implements Comparable<AssetTag> {

    private static final Pattern TAG = Pattern.compile("(?<prefix>[a-z]{2,4})-(?<number>\\d{1,5})");
    private static final Pattern PREFIX = Pattern.compile("[a-z]{2,4}");
    private static final Comparator<AssetTag> ORDER =
            Comparator.comparing(AssetTag::prefix).thenComparingInt(AssetTag::number);

    public AssetTag {
        if (!PREFIX.matcher(prefix).matches()) {
            throw new ValidationError("bad asset tag prefix", "prefix", Details.of("given", prefix));
        }
        if (number <= 0 || number >= 100_000) {
            throw new ValidationError("asset tag number out of range", "number");
        }
    }

    /** Read a tag however it was written: {@code BX-0142}, {@code bx-142}, {@code bx - 142}. */
    public static AssetTag parse(String text) {
        String cleaned = text.strip().toLowerCase(Locale.ROOT).replace(" ", "");
        Matcher match = TAG.matcher(cleaned);
        if (!match.matches()) {
            throw new ValidationError("cannot read asset tag " + Repr.of(text), "asset_tag");
        }
        return new AssetTag(match.group("prefix"), Integer.parseInt(match.group("number")));
    }

    /** The form on the label, zero padded to four so the columns line up. */
    public String printed() {
        return String.format(Locale.ROOT, "%s-%04d", prefix.toUpperCase(Locale.ROOT), number);
    }

    /** The tag after this one on the sheet. */
    public AssetTag next() {
        return new AssetTag(prefix, number + 1);
    }

    @Override
    public int compareTo(AssetTag other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return prefix + "-" + number;
    }
}
