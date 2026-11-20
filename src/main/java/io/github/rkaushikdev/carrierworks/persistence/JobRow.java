package io.github.rkaushikdev.carrierworks.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Check;

/**
 * One programming job, from queued to whatever became of it.
 */
@Entity
@Table(
        name = "jobs",
        indexes = {
            @Index(name = "ix_jobs_board_state", columnList = "board_tag, state"),
            @Index(name = "ix_jobs_digest", columnList = "digest"),
            @Index(name = "ix_jobs_created_at", columnList = "created_at")
        })
@Check(name = "ck_jobs_attempts", constraints = "attempts >= 0")
@Check(name = "ck_jobs_digest", constraints = "length(digest) = 64")
public class JobRow {

    @Id
    @Column(name = "id", nullable = false, length = 24)
    private String id;

    @Column(name = "board_tag", nullable = false, length = 12)
    private String boardTag;

    @Column(name = "digest", nullable = false, length = 64)
    private String digest;

    @Column(name = "mode", nullable = false, length = 16)
    private String mode;

    @Column(name = "requested_by", nullable = false, length = 64)
    private String requestedBy;

    @Column(name = "state", nullable = false, length = 16)
    private String state = "queued";

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "verify", nullable = false)
    private boolean verify = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "not_before")
    private Instant notBefore;

    @Column(name = "error", nullable = false, length = 240)
    private String error = "";

    @Column(name = "error_code", nullable = false, length = 32)
    private String errorCode = "";

    @Column(name = "clocks", nullable = false)
    private int clocks = 0;

    @Column(name = "lease_id", length = 24)
    private String leaseId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "board_tag",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_jobs_board_tag"))
    private BoardRow board;

    /** An empty row, filled in through its setters. */
    public JobRow() {}

    public String getId() {
        return id;
    }

    public void setId(String value) {
        id = value;
    }

    public String getBoardTag() {
        return boardTag;
    }

    public void setBoardTag(String value) {
        boardTag = value;
    }

    public String getDigest() {
        return digest;
    }

    public void setDigest(String value) {
        digest = value;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String value) {
        mode = value;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(String value) {
        requestedBy = value;
    }

    public String getState() {
        return state;
    }

    public void setState(String value) {
        state = value;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int value) {
        attempts = value;
    }

    public boolean isVerify() {
        return verify;
    }

    public void setVerify(boolean value) {
        verify = value;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant value) {
        createdAt = value;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant value) {
        startedAt = value;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant value) {
        finishedAt = value;
    }

    public Instant getNotBefore() {
        return notBefore;
    }

    public void setNotBefore(Instant value) {
        notBefore = value;
    }

    public String getError() {
        return error;
    }

    public void setError(String value) {
        error = value;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String value) {
        errorCode = value;
    }

    public int getClocks() {
        return clocks;
    }

    public void setClocks(int value) {
        clocks = value;
    }

    public String getLeaseId() {
        return leaseId;
    }

    public void setLeaseId(String value) {
        leaseId = value;
    }

    /** The row this one hangs off, read through its key column. */
    public BoardRow getBoard() {
        return board;
    }
}
