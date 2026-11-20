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
import java.time.Instant;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * One visit to a board with a screwdriver or a reference source.
 *
 * <p>Kept forever, like the audit trail, and for the same reason: the question it answers is when a
 * board was last calibrated, and a table somebody can tidy up cannot answer that a year later. Faults
 * cleared is on the row rather than derived, because clearing a fault is a claim the person makes and
 * not something the bench can work out.
 */
@Entity
@Table(
        name = "maintenance",
        indexes = {
            @Index(name = "ix_maintenance_board_at", columnList = "board, at"),
            @Index(name = "ix_maintenance_kind", columnList = "kind")
        })
@Check(name = "ck_maintenance_faults", constraints = "faults_cleared >= 0")
public class MaintenanceRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "board", nullable = false, length = 12)
    private String board;

    @Column(name = "kind", nullable = false, length = 24)
    private String kind;

    @Column(name = "at", nullable = false)
    private Instant at;

    @Column(name = "by", nullable = false, length = 64)
    private String by;

    @Column(name = "notes", nullable = false, length = 240)
    private String notes = "";

    @Column(name = "faults_cleared", nullable = false)
    private int faultsCleared = 0;

    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(
            name = "board",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_maintenance_board"))
    private BoardRow boardRow;

    /** An empty row, filled in through its setters. */
    public MaintenanceRow() {}

    public Integer getId() {
        return id;
    }

    public String getBoard() {
        return board;
    }

    public void setBoard(String value) {
        board = value;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String value) {
        kind = value;
    }

    public Instant getAt() {
        return at;
    }

    public void setAt(Instant value) {
        at = value;
    }

    public String getBy() {
        return by;
    }

    public void setBy(String value) {
        by = value;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String value) {
        notes = value;
    }

    public int getFaultsCleared() {
        return faultsCleared;
    }

    public void setFaultsCleared(int value) {
        faultsCleared = value;
    }

    /** The row this one hangs off, read through its key column. */
    public BoardRow getBoardRow() {
        return boardRow;
    }
}
