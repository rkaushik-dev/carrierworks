package io.github.rkaushikdev.carrierworks.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.hibernate.annotations.Check;

/**
 * One stored image, addressed by what is inside it.
 */
@Entity
@Table(
        name = "artifacts",
        indexes = {
            @Index(name = "ix_artifacts_design", columnList = "design"),
            @Index(name = "ix_artifacts_channel", columnList = "channel"),
            @Index(name = "ix_artifacts_device", columnList = "device")
        })
@Check(name = "ck_artifacts_digest", constraints = "length(digest) = 64")
@Check(name = "ck_artifacts_length", constraints = "length_bytes > 0")
public class ArtifactRow {

    @Id
    @Column(name = "digest", nullable = false, length = 64)
    private String digest;

    @Column(name = "design", nullable = false, length = 64)
    private String design;

    @Column(name = "device", nullable = false, length = 16)
    private String device;

    @Column(name = "length_bytes", nullable = false)
    private int lengthBytes;

    @Column(name = "kind", nullable = false, length = 8)
    private String kind;

    @Column(name = "channel", nullable = false, length = 16)
    private String channel = "dev";

    @Column(name = "source", nullable = false, length = 64)
    private String source = "";

    @Column(name = "tool_version", nullable = false, length = 32)
    private String toolVersion = "";

    @Column(name = "stored_at", nullable = false)
    private Instant storedAt;

    @Column(name = "tags", nullable = false, length = 240)
    private String tags = "";

    @OneToMany(mappedBy = "artifact", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("builtAt")
    private List<ProvenanceRow> builds = new ArrayList<>();

    @OneToMany(mappedBy = "artifact", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BuildReportRow> reports = new ArrayList<>();

    /** An empty row, filled in through its setters. */
    public ArtifactRow() {}

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

    public String getDevice() {
        return device;
    }

    public void setDevice(String value) {
        device = value;
    }

    public int getLengthBytes() {
        return lengthBytes;
    }

    public void setLengthBytes(int value) {
        lengthBytes = value;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String value) {
        kind = value;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String value) {
        channel = value;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String value) {
        source = value;
    }

    public String getToolVersion() {
        return toolVersion;
    }

    public void setToolVersion(String value) {
        toolVersion = value;
    }

    public Instant getStoredAt() {
        return storedAt;
    }

    public void setStoredAt(Instant value) {
        storedAt = value;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String value) {
        tags = value;
    }

    /** The builds that produced this image, oldest first. */
    public List<ProvenanceRow> getBuilds() {
        return Collections.unmodifiableList(builds);
    }

    /** Record one more build of this image. */
    public void addBuild(ProvenanceRow build) {
        build.attachTo(this);
        builds.add(build);
    }

    /** The build reports read for this image. */
    public List<BuildReportRow> getReports() {
        return Collections.unmodifiableList(reports);
    }

    /** Record a build report for this image. */
    public void addReport(BuildReportRow report) {
        report.attachTo(this);
        reports.add(report);
    }
}
