package io.github.rkaushikdev.carrierworks.reports;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.Ids;
import io.github.rkaushikdev.carrierworks.Measure;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The eight numbers that say whether a design closed timing, read from the summary the place and
 * route tool writes.
 *
 * <p>The report is a text file with a table in it, and the table has moved twice in the versions
 * this lab has used, so the parser hunts for the summary row rather than counting lines. What it
 * wants is the eight numbers on that row: worst and total slack for setup and for hold, and the
 * failing and total endpoint counts for each.
 *
 * <p>Hold is the number people forget. A design with negative setup slack runs at a lower clock and
 * is annoying. A design with negative hold slack does not work at any clock, and it is the one that
 * gets shipped because everybody looked at the setup column.
 *
 * <p>Slack is kept in picoseconds because the reports print three decimals of a nanosecond and
 * adding a few thousand of those as floats is how a total ends up disagreeing with the tool by a
 * picosecond, which then looks like a regression.
 *
 * @param wnsPs worst negative slack for setup
 * @param tnsPs total negative slack for setup
 * @param setupFailing setup endpoints that fail
 * @param setupTotal setup endpoints in all
 * @param whsPs worst hold slack
 * @param thsPs total hold slack
 * @param holdFailing hold endpoints that fail
 * @param holdTotal hold endpoints in all
 * @param design the design the report names, as a design name, or empty
 * @param clocks the clocks the report lists, in report order
 */
public record TimingSummary(
        long wnsPs,
        long tnsPs,
        int setupFailing,
        int setupTotal,
        long whsPs,
        long thsPs,
        int holdFailing,
        int holdTotal,
        String design,
        List<ClockConstraint> clocks) {

    private static final String NUMBER = "-?\\d+(?:\\.\\d+)?";
    private static final Pattern SUMMARY_ROW = Pattern.compile("\\|?\\s*(?<wns>" + NUMBER + ")\\s*\\|"
            + "\\s*(?<tns>" + NUMBER + ")\\s*\\|"
            + "\\s*(?<tnsFailing>\\d+)\\s*\\|"
            + "\\s*(?<tnsTotal>\\d+)\\s*\\|"
            + "\\s*(?<whs>" + NUMBER + ")\\s*\\|"
            + "\\s*(?<ths>" + NUMBER + ")\\s*\\|"
            + "\\s*(?<thsFailing>\\d+)\\s*\\|"
            + "\\s*(?<thsTotal>\\d+)\\s*\\|?\\s*");
    private static final Pattern CLOCK_ROW = Pattern.compile("\\s*(?<name>[a-zA-Z][\\w/\\[\\].]*)\\s+"
            + "\\{(?<waveform>[^}]*)\\}\\s+"
            + "(?<period>" + NUMBER + ")\\s+"
            + "(?<frequency>" + NUMBER + ")\\s*");

    public TimingSummary {
        if (!design.isEmpty()) {
            design = Ids.designName(design);
        }
        for (int count : new int[] {setupFailing, setupTotal, holdFailing, holdTotal}) {
            if (count < 0) {
                throw new ValidationError("endpoint counts are not negative", "endpoints");
            }
        }
        if (setupFailing > setupTotal || holdFailing > holdTotal) {
            throw new ValidationError(
                    "more endpoints failed than exist",
                    "endpoints",
                    Details.of("setup", setupFailing + "/" + setupTotal, "hold", holdFailing + "/" + holdTotal));
        }
        clocks = List.copyOf(clocks);
    }

    /** The eight numbers on their own, with no design named and no clocks. */
    public TimingSummary(
            long wnsPs,
            long tnsPs,
            int setupFailing,
            int setupTotal,
            long whsPs,
            long thsPs,
            int holdFailing,
            int holdTotal) {
        this(wnsPs, tnsPs, setupFailing, setupTotal, whsPs, thsPs, holdFailing, holdTotal, "", List.of());
    }

    /**
     * Pull the summary out of a report, wherever the tool put it.
     *
     * <p>The first summary row wins when a report holds several, the leading bar on it is optional,
     * the design comes from the first {@code Design : name} line, and the clocks from every row of
     * the clock table.
     *
     * @throws ValidationError when the report holds no summary row, or the row does not hold up
     */
    public static TimingSummary parse(String report) {
        String design = "";
        List<ClockConstraint> clocks = new ArrayList<>();
        TimingSummary summary = null;
        for (String line : report.lines().toList()) {
            if (design.isEmpty()) {
                design = ReportLines.design(line).orElse("");
            }
            Matcher clock = CLOCK_ROW.matcher(line);
            if (clock.matches()) {
                clocks.add(new ClockConstraint(
                        clock.group("name"),
                        Measure.picosecondsFromNs(clock.group("period")),
                        Double.parseDouble(clock.group("frequency"))));
                continue;
            }
            Matcher row = SUMMARY_ROW.matcher(line.strip());
            if (row.matches() && summary == null) {
                summary = new TimingSummary(
                        Measure.picosecondsFromNs(row.group("wns")),
                        Measure.picosecondsFromNs(row.group("tns")),
                        Integer.parseInt(row.group("tnsFailing")),
                        Integer.parseInt(row.group("tnsTotal")),
                        Measure.picosecondsFromNs(row.group("whs")),
                        Measure.picosecondsFromNs(row.group("ths")),
                        Integer.parseInt(row.group("thsFailing")),
                        Integer.parseInt(row.group("thsTotal")));
            }
        }
        if (summary == null) {
            throw new ValidationError("no timing summary in that report", "report");
        }
        return new TimingSummary(
                summary.wnsPs,
                summary.tnsPs,
                summary.setupFailing,
                summary.setupTotal,
                summary.whsPs,
                summary.thsPs,
                summary.holdFailing,
                summary.holdTotal,
                design,
                clocks);
    }

    /** Whether setup closed: no negative worst slack and nothing failing. */
    public boolean setupMet() {
        return wnsPs >= 0 && setupFailing == 0;
    }

    /** Whether hold closed: no negative worst slack and nothing failing. */
    public boolean holdMet() {
        return whsPs >= 0 && holdFailing == 0;
    }

    /** Whether the design closed, both ways. */
    public boolean met() {
        return setupMet() && holdMet();
    }

    /** The worse of the two worst slacks, which is what a gate reads. */
    public long worstSlackPs() {
        return Math.min(wnsPs, whsPs);
    }

    /** Setup and hold endpoints failing between them. */
    public int failingEndpoints() {
        return setupFailing + holdFailing;
    }

    /** The clock with the highest frequency, the first of them on a tie. */
    public Optional<ClockConstraint> fastestClock() {
        return clocks.stream().max(Comparator.comparingDouble(ClockConstraint::frequencyMhz));
    }

    /** {@code radar_frontend closed: wns 0.147 ns, whs 0.021 ns, 0 failing}. */
    public String describe() {
        String verdict = met() ? "closed" : "did not close";
        String name = design.isEmpty() ? "" : design + " ";
        return name + verdict + ": wns " + Measure.renderNs(wnsPs) + " ns, whs " + Measure.renderNs(whsPs) + " ns, "
                + failingEndpoints() + " failing";
    }
}
