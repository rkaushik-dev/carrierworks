package io.github.rkaushikdev.carrierworks.catalog;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.JunctionRange;
import io.github.rkaushikdev.carrierworks.Requirement;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.util.JsonValues;
import io.github.rkaushikdev.carrierworks.util.Repr;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * One ordering code, held apart at the seams.
 *
 * <p>An ordering code like {@code XC7A100T-2FGG484I} is five facts glued together with no
 * separators, and the lab needs all five for different reasons. The device decides which bitstream
 * will load. The speed grade decides whether a timing report from one board means anything on
 * another. The package decides which carrier the board can be. The temperature grade decides how
 * hot the thermal supervisor is allowed to let it get. The pin count is how anyone tells two
 * otherwise identical boards apart across the bench.
 *
 * <p>Only Xilinx codes are parsed here. Lattice and Intel do not follow this grammar at all, which
 * is why the parser is not written as if one regular expression could cover the world. Everything
 * is stored lower case: half the sources of truth shout their part numbers while the other half
 * whisper them.
 *
 * @param device the silicon, such as {@code xc7a100t}
 * @param speedGrade the speed bin, such as {@code 2} or {@code 1l}
 * @param packageCode the package, such as {@code fgg484}
 * @param temperature the temperature grade letter
 */
public record PartNumber(String device, String speedGrade, String packageCode, String temperature) {

    /**
     * Junction temperature range in celsius for each ordering code suffix. These are the datasheet
     * numbers, and the thermal supervisor reads them rather than carrying a threshold of its own.
     */
    public static final Map<String, JunctionRange> TEMPERATURE_RANGES = CatalogFile.parts("temperature_grades").stream()
            .collect(Collectors.toUnmodifiableMap(
                    entry -> JsonValues.text(entry.get("grade")),
                    entry -> new JunctionRange(
                            CatalogFile.whole(entry, "low", 0), CatalogFile.whole(entry, "high", 0))));

    private static final Pattern DEVICE = Pattern.compile(
            "xc(?:7(?<seven>[akvsz])(?<sevenSize>\\d+[a-z]*)|6s(?<six>lx|vlx)(?<sixSize>\\d+t?)|(?<ultra>ku|vu|zu|au)(?<ultraSize>\\d+[a-z]*))");

    private static final Pattern ORDERING =
            Pattern.compile("(?<device>xc[a-z0-9]+?)-(?<speed>\\dl?)(?<package>[a-z]+\\d+)(?<temperature>[cieqm])");

    private static final Pattern PIN_COUNT = Pattern.compile("\\d+$");

    private static final Map<String, String> SEVEN_FAMILIES = Map.of(
            "a", "artix7",
            "k", "kintex7",
            "v", "virtex7",
            "s", "spartan7",
            "z", "zynq7000");

    private static final Map<String, String> ULTRA_FAMILIES = Map.of(
            "ku", "kintex_ultrascale",
            "vu", "virtex_ultrascale",
            "zu", "zynq_ultrascale",
            "au", "artix_ultrascale");

    public PartNumber {
        if (!TEMPERATURE_RANGES.containsKey(temperature)) {
            throw new ValidationError(
                    "unknown temperature grade " + Repr.of(temperature), "temperature", Details.of("device", device));
        }
        familyOf(device);
    }

    /** Take an ordering code apart, however it was typed. */
    public static PartNumber parse(String text) {
        String cleaned = text.strip().toLowerCase(Locale.ROOT).replace(" ", "");
        if (cleaned.isEmpty()) {
            throw new ValidationError("empty part number", "part_number");
        }
        Matcher match = ORDERING.matcher(cleaned);
        if (!match.matches()) {
            throw new ValidationError("cannot read part number " + Repr.of(text), "part_number");
        }
        return new PartNumber(
                match.group("device"), match.group("speed"), match.group("package"), match.group("temperature"));
    }

    private static String familyOf(String device) {
        Matcher match = DEVICE.matcher(device);
        if (!match.matches()) {
            throw new ValidationError("not a xilinx device code: " + device, "device");
        }
        if (match.group("seven") != null) {
            return SEVEN_FAMILIES.get(match.group("seven"));
        }
        if (match.group("six") != null) {
            return "spartan6";
        }
        return ULTRA_FAMILIES.get(match.group("ultra"));
    }

    /** The device family, such as {@code artix7}. */
    public String family() {
        return familyOf(device);
    }

    /** Who makes it; only Xilinx codes are read here. */
    public String vendor() {
        return "xilinx";
    }

    /** The pin count at the end of the package code. */
    public int pinCount() {
        Matcher digits = PIN_COUNT.matcher(packageCode);
        if (!digits.find()) {
            throw new ValidationError("package has no pin count", "package");
        }
        return Integer.parseInt(digits.group());
    }

    /** The L screens are the low static power bins. */
    public boolean lowPower() {
        return speedGrade.contains("l");
    }

    /** The junction temperatures the grade is specified for. */
    public JunctionRange junctionRange() {
        return TEMPERATURE_RANGES.get(temperature);
    }

    /** Higher is faster. A 2L sits below a plain 2 and above a 1, the way a requirement ranks them. */
    public int speedRank() {
        return Requirement.gradeRank(speedGrade).orElseThrow();
    }

    /** Back to the form printed on the lid. */
    public String orderingCode() {
        return (device + "-" + speedGrade + packageCode + temperature).toUpperCase(Locale.ROOT);
    }

    /**
     * Whether a board fitted with this part can stand in for another.
     *
     * <p>Same silicon and same package, and at least the speed the caller asked for. A faster part
     * runs a slower one's bitstream; the reverse is how a regression turns into a week of confusion.
     */
    public boolean fits(PartNumber other) {
        if (!device.equals(other.device) || !packageCode.equals(other.packageCode)) {
            return false;
        }
        return speedRank() >= other.speedRank();
    }

    @Override
    public String toString() {
        return orderingCode();
    }
}
