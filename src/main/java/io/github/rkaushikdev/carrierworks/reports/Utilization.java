package io.github.rkaushikdev.carrierworks.reports;

import io.github.rkaushikdev.carrierworks.Ids;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.catalog.DeviceSpec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How much of a device a design used, in the four numbers the bench compares on.
 *
 * <p>The utilization report is a set of tables, one per site type family, and the bench wants four
 * rows out of all of them: logic, registers, block memory and the multiplier slices ({@link
 * SiteKind}). Those four are what decide whether a design will fit a smaller part, which is the
 * question the scheduler asks when somebody books the only Kintex to run something an Artix would
 * hold.
 *
 * <p>What has never changed between tool versions is the shape of a row: a name, a used count, a
 * fixed count nobody reads, sometimes a prohibited count, an available count and a percentage the
 * parser recomputes rather than trusts.
 *
 * @param rows every table row in the report, in report order
 * @param design the design the report names, as a design name, or empty
 */
public record Utilization(List<SiteUsage> rows, String design) {

    private static final Pattern ROW = Pattern.compile("\\|\\s*(?<name>[A-Za-z][\\w\\s.\\-()/]*?)\\s*\\|"
            + "\\s*(?<used>\\d+)\\s*\\|"
            + "\\s*(?<fixed>\\d+)\\s*\\|"
            + "(?:\\s*(?<prohibited>\\d+)\\s*\\|)?"
            + "\\s*(?<available>\\d+)\\s*\\|"
            + "\\s*(?<percent>\\d+(?:\\.\\d+)?)\\s*\\|\\s*");
    private static final Set<String> HEADINGS = Set.of("site type", "ref name");
    private static final Comparator<SiteUsage> FULLNESS =
            Comparator.comparingDouble(SiteUsage::percent).thenComparing(SiteUsage::name);

    public Utilization {
        rows = List.copyOf(rows);
        if (!design.isEmpty()) {
            design = Ids.designName(design);
        }
    }

    /** Rows with no design named. */
    public Utilization(List<SiteUsage> rows) {
        this(rows, "");
    }

    /**
     * Read every table row in a report, whichever table it was in.
     *
     * <p>Header rows are skipped and the design comes from the first {@code Design : name} line.
     *
     * @throws ValidationError when the report holds no table rows at all, or a row does not hold up
     */
    public static Utilization parse(String report) {
        List<SiteUsage> rows = new ArrayList<>();
        String design = "";
        for (String line : report.lines().toList()) {
            if (design.isEmpty()) {
                design = ReportLines.design(line).orElse("");
            }
            Matcher row = ROW.matcher(line);
            if (!row.matches()) {
                continue;
            }
            String name = row.group("name").strip();
            if (HEADINGS.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            rows.add(
                    new SiteUsage(name, Integer.parseInt(row.group("used")), Integer.parseInt(row.group("available"))));
        }
        if (rows.isEmpty()) {
            throw new ValidationError("no utilization tables in that report", "report");
        }
        return new Utilization(rows, design);
    }

    /** The row for one of the four kinds, whatever the tool called it. */
    public Optional<SiteUsage> row(SiteKind kind) {
        return rows.stream().filter(row -> kind.names(row.name())).findFirst();
    }

    /** How many sites of one kind the design used; a kind the report does not list is none. */
    public int used(SiteKind kind) {
        return row(kind).map(SiteUsage::used).orElse(0);
    }

    /** Lookup tables used. */
    public int luts() {
        return used(SiteKind.LUTS);
    }

    /** Registers used. */
    public int registers() {
        return used(SiteKind.REGISTERS);
    }

    /** Block memory tiles used. */
    public int blockRam() {
        return used(SiteKind.BLOCK_RAM);
    }

    /** Multiplier slices used. */
    public int dsp() {
        return used(SiteKind.DSP);
    }

    /**
     * The row nearest full, which is the one that will stop a design fitting; on a tie, the one whose
     * name sorts last.
     */
    public Optional<SiteUsage> busiest() {
        return rows.stream().max(FULLNESS);
    }

    /** Every row at least this full, in report order. */
    public List<SiteUsage> over(double percent) {
        return rows.stream().filter(row -> row.percent() >= percent).toList();
    }

    /** Whether this design would fit another part, by the four numbers. */
    public boolean fits(DeviceSpec device) {
        return device.holds(luts(), registers(), blockRam(), dsp());
    }

    /** {@code radar_frontend: 45123 luts, 61234 registers, busiest Block RAM Tile 145/445 (32.6%)}. */
    public String describe() {
        String name = design.isEmpty() ? "" : design + ": ";
        return busiest()
                .map(row -> name + luts() + " luts, " + registers() + " registers, busiest " + row.describe())
                .orElse(name + "nothing used");
    }
}
