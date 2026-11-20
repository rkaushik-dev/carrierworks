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
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * One connector on a carrier and whatever is in it.
 */
@Entity
@Table(
        name = "board_slots",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_slots_name",
                    columnNames = {"board_tag", "name"})
        },
        indexes = {@Index(name = "ix_slots_fitted", columnList = "fitted")})
public class SlotRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "board_tag", nullable = false, length = 12)
    private String boardTag;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "connector", nullable = false, length = 64)
    private String connector;

    @Column(name = "fitted", length = 64)
    private String fitted;

    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(
            name = "board_tag",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_board_slots_board_tag"))
    private BoardRow board;

    /** An empty row, filled in through its setters. */
    public SlotRow() {}

    public Integer getId() {
        return id;
    }

    public String getBoardTag() {
        return boardTag;
    }

    public void setBoardTag(String value) {
        boardTag = value;
    }

    public String getName() {
        return name;
    }

    public void setName(String value) {
        name = value;
    }

    public String getConnector() {
        return connector;
    }

    public void setConnector(String value) {
        connector = value;
    }

    public String getFitted() {
        return fitted;
    }

    public void setFitted(String value) {
        fitted = value;
    }

    /** The row this one hangs off, read through its key column. */
    public BoardRow getBoard() {
        return board;
    }

    /** Point this row at its board, key column and all. */
    void attachTo(BoardRow owner) {
        boardTag = owner.getTag();
        board = owner;
    }
}
