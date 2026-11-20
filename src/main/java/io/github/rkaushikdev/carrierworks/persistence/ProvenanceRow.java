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
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * One build that produced an image.
 *
 * <p>Several builds can produce the same image, so the key is the pair rather than the digest. That is
 * not a hypothetical: rebuilding a tag on a different agent does it every time.
 */
@Entity
@Table(
        name = "provenance",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_provenance_build",
                    columnNames = {"digest", "build_id"})
        },
        indexes = {@Index(name = "ix_provenance_revision", columnList = "source_revision")})
public class ProvenanceRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "digest", nullable = false, length = 64)
    private String digest;

    @Column(name = "design", nullable = false, length = 64)
    private String design;

    @Column(name = "source_revision", nullable = false, length = 40)
    private String sourceRevision;

    @Column(name = "build_id", nullable = false, length = 64)
    private String buildId;

    @Column(name = "built_at", nullable = false)
    private Instant builtAt;

    @Column(name = "tool_version", nullable = false, length = 32)
    private String toolVersion = "";

    @Column(name = "constraints_digest", nullable = false, length = 64)
    private String constraintsDigest = "";

    @Column(name = "builder", nullable = false, length = 64)
    private String builder = "";

    @Column(name = "branch", nullable = false, length = 64)
    private String branch = "";

    @Column(name = "dirty", nullable = false)
    private boolean dirty = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(
            name = "digest",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_provenance_digest"))
    private ArtifactRow artifact;

    /** An empty row, filled in through its setters. */
    public ProvenanceRow() {}

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

    public String getSourceRevision() {
        return sourceRevision;
    }

    public void setSourceRevision(String value) {
        sourceRevision = value;
    }

    public String getBuildId() {
        return buildId;
    }

    public void setBuildId(String value) {
        buildId = value;
    }

    public Instant getBuiltAt() {
        return builtAt;
    }

    public void setBuiltAt(Instant value) {
        builtAt = value;
    }

    public String getToolVersion() {
        return toolVersion;
    }

    public void setToolVersion(String value) {
        toolVersion = value;
    }

    public String getConstraintsDigest() {
        return constraintsDigest;
    }

    public void setConstraintsDigest(String value) {
        constraintsDigest = value;
    }

    public String getBuilder() {
        return builder;
    }

    public void setBuilder(String value) {
        builder = value;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String value) {
        branch = value;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean value) {
        dirty = value;
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
