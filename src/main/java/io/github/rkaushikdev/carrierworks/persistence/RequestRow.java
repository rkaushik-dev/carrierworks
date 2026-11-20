package io.github.rkaushikdev.carrierworks.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Check;

/**
 * Somebody waiting for a board they have not got yet.
 *
 * <p>The requirements are kept as the text they were written as rather than as a parsed structure,
 * because that text is what the person typed and it is what they will be shown when the request is
 * refused. Parsing it back is cheap and lossless; storing the parse is neither.
 */
@Entity
@Table(
        name = "requests",
        indexes = {
            @Index(name = "ix_requests_open", columnList = "satisfied_at, cancelled_at"),
            @Index(name = "ix_requests_holder", columnList = "holder")
        })
@Check(name = "ck_requests_priority", constraints = "priority between 1 and 9")
@Check(name = "ck_requests_minutes", constraints = "minutes > 0")
public class RequestRow {

    @Id
    @Column(name = "id", nullable = false, length = 16)
    private String id;

    @Column(name = "holder", nullable = false, length = 64)
    private String holder;

    @Column(name = "purpose", nullable = false, length = 120)
    private String purpose;

    @Column(name = "requirements", nullable = false, length = 500)
    private String requirements = "";

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "priority", nullable = false)
    private int priority = 5;

    @Column(name = "minutes", nullable = false)
    private int minutes = 60;

    @Column(name = "preemptible", nullable = false)
    private boolean preemptible = true;

    @Column(name = "needs_hands", nullable = false)
    private boolean needsHands = false;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "satisfied_at")
    private Instant satisfiedAt;

    @Column(name = "assigned_board", nullable = false, length = 12)
    private String assignedBoard = "";

    /** An empty row, filled in through its setters. */
    public RequestRow() {}

    public String getId() {
        return id;
    }

    public void setId(String value) {
        id = value;
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

    public String getRequirements() {
        return requirements;
    }

    public void setRequirements(String value) {
        requirements = value;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Instant value) {
        submittedAt = value;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int value) {
        priority = value;
    }

    public int getMinutes() {
        return minutes;
    }

    public void setMinutes(int value) {
        minutes = value;
    }

    public boolean isPreemptible() {
        return preemptible;
    }

    public void setPreemptible(boolean value) {
        preemptible = value;
    }

    public boolean isNeedsHands() {
        return needsHands;
    }

    public void setNeedsHands(boolean value) {
        needsHands = value;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(Instant value) {
        cancelledAt = value;
    }

    public Instant getSatisfiedAt() {
        return satisfiedAt;
    }

    public void setSatisfiedAt(Instant value) {
        satisfiedAt = value;
    }

    public String getAssignedBoard() {
        return assignedBoard;
    }

    public void setAssignedBoard(String value) {
        assignedBoard = value;
    }
}
