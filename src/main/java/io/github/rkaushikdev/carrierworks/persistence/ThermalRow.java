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
 * One temperature reading off one board.
 *
 * <p>The highest volume table on the bench by a wide margin: every board is polled every twenty
 * seconds, so a full rack writes about four thousand rows an hour. It is written to constantly and read
 * in windows, which is why the only index is the one a window query uses, and why the sweep that drops
 * old rows is part of the same service rather than a cron job nobody remembers.
 */
@Entity
@Table(
        name = "thermal_samples",
        indexes = {@Index(name = "ix_thermal_board_at", columnList = "board, at")})
@Check(name = "ck_thermal_range", constraints = "millidegrees between -60000 and 200000")
public class ThermalRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "board", nullable = false, length = 12)
    private String board;

    @Column(name = "at", nullable = false)
    private Instant at;

    @Column(name = "millidegrees", nullable = false)
    private int millidegrees;

    @Column(name = "source", nullable = false, length = 16)
    private String source = "sysmon";

    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(
            name = "board",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_thermal_samples_board"))
    private BoardRow boardRow;

    /** An empty row, filled in through its setters. */
    public ThermalRow() {}

    public Integer getId() {
        return id;
    }

    public String getBoard() {
        return board;
    }

    public void setBoard(String value) {
        board = value;
    }

    public Instant getAt() {
        return at;
    }

    public void setAt(Instant value) {
        at = value;
    }

    public int getMillidegrees() {
        return millidegrees;
    }

    public void setMillidegrees(int value) {
        millidegrees = value;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String value) {
        source = value;
    }

    /** The row this one hangs off, read through its key column. */
    public BoardRow getBoardRow() {
        return boardRow;
    }
}
