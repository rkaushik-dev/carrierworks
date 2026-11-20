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
 * One hold on one board, live or closed.
 *
 * <p>One board can have any number of closed leases and at most one open one. The database says so
 * through {@code open_board_tag}, a column it computes to hold the board only while the lease is open,
 * and a unique index over it: two writers racing to take the same board produce an error rather than
 * two holders.
 */
@Entity
@Table(
        name = "leases",
        indexes = {
            @Index(name = "uq_leases_active_board", columnList = "open_board_tag", unique = true),
            @Index(name = "ix_leases_holder", columnList = "holder"),
            @Index(name = "ix_leases_taken_at", columnList = "taken_at")
        })
@Check(name = "ck_leases_priority", constraints = "priority between 1 and 9")
@Check(name = "ck_leases_window", constraints = "expires_at > taken_at")
public class LeaseRow {

    @Id
    @Column(name = "id", nullable = false, length = 24)
    private String id;

    @Column(name = "board_tag", nullable = false, length = 12)
    private String boardTag;

    @Column(name = "holder", nullable = false, length = 64)
    private String holder;

    @Column(name = "purpose", nullable = false, length = 240)
    private String purpose;

    @Column(name = "taken_at", nullable = false)
    private Instant takenAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "priority", nullable = false)
    private int priority = 5;

    @Column(name = "renewals", nullable = false)
    private int renewals = 0;

    @Column(name = "preemptible", nullable = false)
    private boolean preemptible = true;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "release_reason", nullable = false, length = 120)
    private String releaseReason = "";

    /** Computed by the database: the board while the lease is open, otherwise null. */
    @Column(
            name = "open_board_tag",
            length = 12,
            insertable = false,
            updatable = false,
            columnDefinition = "varchar(12) generated always as (case when released_at is null then board_tag end)")
    private String openBoardTag;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "board_tag",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_leases_board_tag"))
    private BoardRow board;

    /** An empty row, filled in through its setters. */
    public LeaseRow() {}

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

    public String getHolder() {
        return holder;
    }

    public void setHolder(String value) {
        holder = value;
    }

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String value) {
        purpose = value;
    }

    public Instant getTakenAt() {
        return takenAt;
    }

    public void setTakenAt(Instant value) {
        takenAt = value;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant value) {
        expiresAt = value;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int value) {
        priority = value;
    }

    public int getRenewals() {
        return renewals;
    }

    public void setRenewals(int value) {
        renewals = value;
    }

    public boolean isPreemptible() {
        return preemptible;
    }

    public void setPreemptible(boolean value) {
        preemptible = value;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(Instant value) {
        releasedAt = value;
    }

    public String getReleaseReason() {
        return releaseReason;
    }

    public void setReleaseReason(String value) {
        releaseReason = value;
    }

    /** The row this one hangs off, read through its key column. */
    public BoardRow getBoard() {
        return board;
    }
}
