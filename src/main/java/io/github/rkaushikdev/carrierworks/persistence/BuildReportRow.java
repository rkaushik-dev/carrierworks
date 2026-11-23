package io.github.rkaushikdev.carrierworks.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * The numbers out of one build's timing and utilization reports.
 */
@Entity
@Table(
        name = "build_reports",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_build_reports_digest",
                    columnNames = {"digest"})
        },
        indexes = {@Index(name = "ix_build_reports_design", columnList = "design")})
@Check(name = "ck_reports_setup_failing", constraints = "setup_failing >= 0")
@Check(name = "ck_reports_hold_failing", constraints = "hold_failing >= 0")
@Check(name = "ck_reports_pulse_failing", constraints = "pulse_failing >= 0")
public class BuildReportRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "digest", nullable = false, length = 64)
    private String digest;

    @Column(name = "design", nullable = false, length = 64)
    private String design;

    @Column(name = "read_at", nullable = false)
    private Instant readAt;

    @Column(name = "wns_ps", nullable = false)
    private int wnsPs;

    @Column(name = "tns_ps", nullable = false)
    private int tnsPs;

    @Column(name = "setup_failing", nullable = false)
    private int setupFailing = 0;

    @Column(name = "setup_total", nullable = false)
    private int setupTotal = 0;

    @Column(name = "whs_ps", nullable = false)
    private int whsPs;

    @Column(name = "ths_ps", nullable = false)
    private int thsPs;

    @Column(name = "hold_failing", nullable = false)
    private int holdFailing = 0;

    @Column(name = "hold_total", nullable = false)
    private int holdTotal = 0;

    @Column(name = "wpws_ps", nullable = false)
    private int wpwsPs = 0;

    @Column(name = "tpws_ps", nullable = false)
    private int tpwsPs = 0;

    @Column(name = "pulse_failing", nullable = false)
    private int pulseFailing = 0;

    @Column(name = "pulse_total", nullable = false)
    private int pulseTotal = 0;

    @Column(name = "luts", nullable = false)
    private int luts = 0;

    @Column(name = "registers", nullable = false)
    private int registers = 0;

    @Column(name = "block_ram", nullable = false)
    private int blockRam = 0;

    @Column(name = "dsp", nullable = false)
    private int dsp = 0;

    @Column(name = "is_baseline", nullable = false)
    private boolean baseline = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(
            name = "digest",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_build_reports_digest"))
    private ArtifactRow artifact;

    /** An empty row, filled in through its setters. */
    public BuildReportRow() {}

    public Integer getId() {
        return id;
    }

    public String getDigest() {
        return digest;
    }

    public void setDigest(String value) {
        digest = value;
    }

    public String getDesign() {
        return design;
    }

    public void setDesign(String value) {
        design = value;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public void setReadAt(Instant value) {
        readAt = value;
    }

    public int getWnsPs() {
        return wnsPs;
    }

    public void setWnsPs(int value) {
        wnsPs = value;
    }

    public int getTnsPs() {
        return tnsPs;
    }

    public void setTnsPs(int value) {
        tnsPs = value;
    }

    public int getSetupFailing() {
        return setupFailing;
    }

    public void setSetupFailing(int value) {
        setupFailing = value;
    }

    public int getSetupTotal() {
        return setupTotal;
    }

    public void setSetupTotal(int value) {
        setupTotal = value;
    }

    public int getWhsPs() {
        return whsPs;
    }

    public void setWhsPs(int value) {
        whsPs = value;
    }

    public int getThsPs() {
        return thsPs;
    }

    public void setThsPs(int value) {
        thsPs = value;
    }

    public int getHoldFailing() {
        return holdFailing;
    }

    public void setHoldFailing(int value) {
        holdFailing = value;
    }

    public int getHoldTotal() {
        return holdTotal;
    }

    public void setHoldTotal(int value) {
        holdTotal = value;
    }

    public int getWpwsPs() {
        return wpwsPs;
    }

    public void setWpwsPs(int value) {
        wpwsPs = value;
    }

    public int getTpwsPs() {
        return tpwsPs;
    }

    public void setTpwsPs(int value) {
        tpwsPs = value;
    }

    public int getPulseFailing() {
        return pulseFailing;
    }

    public void setPulseFailing(int value) {
        pulseFailing = value;
    }

    public int getPulseTotal() {
        return pulseTotal;
    }

    public void setPulseTotal(int value) {
        pulseTotal = value;
    }

    public int getLuts() {
        return luts;
    }

    public void setLuts(int value) {
        luts = value;
    }

    public int getRegisters() {
        return registers;
    }

    public void setRegisters(int value) {
        registers = value;
    }

    public int getBlockRam() {
        return blockRam;
    }

    public void setBlockRam(int value) {
        blockRam = value;
    }

    public int getDsp() {
        return dsp;
    }

    public void setDsp(int value) {
        dsp = value;
    }

    public boolean isBaseline() {
        return baseline;
    }

    public void setBaseline(boolean value) {
        baseline = value;
    }

    /** The row this one hangs off, read through its key column. */
    public ArtifactRow getArtifact() {
        return artifact;
    }

    /** Point this row at its image, key column and all. */
    void attachTo(ArtifactRow owner) {
        digest = owner.getDigest();
        artifact = owner;
    }
}
