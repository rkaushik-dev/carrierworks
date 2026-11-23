package io.github.rkaushikdev.carrierworks.persistence;

import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.catalog.DeviceSpec;
import io.github.rkaushikdev.carrierworks.catalog.Devices;
import io.github.rkaushikdev.carrierworks.reports.BuildReport;
import io.github.rkaushikdev.carrierworks.reports.PulseWidth;
import io.github.rkaushikdev.carrierworks.reports.SiteUsage;
import io.github.rkaushikdev.carrierworks.reports.TimingSummary;
import io.github.rkaushikdev.carrierworks.reports.Utilization;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** The numbers out of each build's timing and utilization reports. */
public final class BuildReportRepository {

    private final EntityManager session;

    BuildReportRepository(EntityManager session) {
        this.session = session;
    }

    /** The session this repository reads and writes through. */
    public EntityManager session() {
        return session;
    }

    /** Record the numbers out of one build, read at a moment. */
    public BuildReport add(BuildReport report, Instant readAt) {
        BuildReportRow row = new BuildReportRow();
        row.setDigest(report.digest());
        row.setDesign(report.design());
        row.setReadAt(readAt);
        TimingSummary timing = report.timing();
        row.setWnsPs(Math.toIntExact(timing.wnsPs()));
        row.setTnsPs(Math.toIntExact(timing.tnsPs()));
        row.setSetupFailing(timing.setupFailing());
        row.setSetupTotal(timing.setupTotal());
        row.setWhsPs(Math.toIntExact(timing.whsPs()));
        row.setThsPs(Math.toIntExact(timing.thsPs()));
        row.setHoldFailing(timing.holdFailing());
        row.setHoldTotal(timing.holdTotal());
        PulseWidth pulse = timing.pulseWidth();
        row.setWpwsPs(Math.toIntExact(pulse.wpwsPs()));
        row.setTpwsPs(Math.toIntExact(pulse.tpwsPs()));
        row.setPulseFailing(pulse.failing());
        row.setPulseTotal(pulse.total());
        row.setLuts(report.utilization().luts());
        row.setRegisters(report.utilization().registers());
        row.setBlockRam(report.utilization().blockRam());
        row.setDsp(report.utilization().dsp());
        session.persist(row);
        return report;
    }

    /** The report for one image, if one was read. */
    public Optional<BuildReport> forDigest(String digest) {
        return row(digest).map(this::report);
    }

    /** Every report for one design, oldest first. */
    public List<BuildReport> forDesign(String design) {
        return session
                .createQuery(
                        "select r from BuildReportRow r where r.design = :design order by r.readAt",
                        BuildReportRow.class)
                .setParameter("design", design.strip().toLowerCase(Locale.ROOT))
                .getResultList()
                .stream()
                .map(this::report)
                .toList();
    }

    /** The build pinned as a design's baseline, if one is. */
    public Optional<BuildReport> baseline(String design) {
        return session.createQuery(
                        "select r from BuildReportRow r where r.design = :design and r.baseline = true",
                        BuildReportRow.class)
                .setParameter("design", design.strip().toLowerCase(Locale.ROOT))
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .map(this::report);
    }

    /**
     * Pin one build as its design's baseline, unpinning whatever was.
     *
     * <p>One baseline per design, so the old one is cleared in the same transaction. Two baselines
     * would make a regression report depend on which row came back first.
     */
    public BuildReport setBaseline(String digest) {
        BuildReportRow pinned = row(digest).orElseThrow(() -> new NotFound("build report", digest));
        session.createQuery("select r from BuildReportRow r where r.design = :design", BuildReportRow.class)
                .setParameter("design", pinned.getDesign())
                .getResultList()
                .forEach(other -> other.setBaseline(other.getDigest().equals(digest)));
        return report(pinned);
    }

    private Optional<BuildReportRow> row(String digest) {
        return session.createQuery("select r from BuildReportRow r where r.digest = :digest", BuildReportRow.class)
                .setParameter("digest", digest)
                .setMaxResults(1)
                .getResultStream()
                .findFirst();
    }

    /**
     * A report back out of its row, with the utilization rows rebuilt.
     *
     * <p>Only the four counts the bench compares on are stored, not the available column, so the
     * rebuilt rows take their capacity from the device the image was built for. Rebuilding them with
     * the used count as the capacity read every stored row as a hundred percent full, and a design
     * that grew by a third compared clean against its baseline. An image for a device the catalogue
     * does not know keeps that old shape, since there is nothing better to measure it against.
     */
    private BuildReport report(BuildReportRow row) {
        TimingSummary timing = new TimingSummary(
                row.getWnsPs(),
                row.getTnsPs(),
                row.getSetupFailing(),
                row.getSetupTotal(),
                row.getWhsPs(),
                row.getThsPs(),
                row.getHoldFailing(),
                row.getHoldTotal(),
                row.getDesign(),
                List.of(),
                new PulseWidth(row.getWpwsPs(), row.getTpwsPs(), row.getPulseFailing(), row.getPulseTotal()));
        Optional<DeviceSpec> device = Optional.ofNullable(session.find(ArtifactRow.class, row.getDigest()))
                .flatMap(artifact -> Devices.lookup(artifact.getDevice()));
        Utilization utilization = new Utilization(
                List.of(
                        rebuilt("Slice LUTs", row.getLuts(), device.map(DeviceSpec::luts)),
                        rebuilt("Slice Registers", row.getRegisters(), device.map(DeviceSpec::flipFlops)),
                        rebuilt("Block RAM Tile", row.getBlockRam(), device.map(DeviceSpec::blockRam36k)),
                        rebuilt("DSPs", row.getDsp(), device.map(DeviceSpec::dspSlices))),
                row.getDesign());
        return new BuildReport(row.getDigest(), row.getDesign(), row.getReadAt(), timing, utilization);
    }

    private static SiteUsage rebuilt(String name, int used, Optional<Integer> capacity) {
        return new SiteUsage(name, used, Math.max(capacity.orElse(used), Math.max(used, 1)));
    }
}
