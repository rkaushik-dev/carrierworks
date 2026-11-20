package io.github.rkaushikdev.carrierworks.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * Where a board is now, kept beside it rather than on it.
 */
@Entity
@Table(
        name = "board_states",
        indexes = {@Index(name = "ix_board_states_state", columnList = "state")})
@Check(name = "ck_board_states_state", constraints = "length(state) > 0")
public class BoardStateRow {

    @Id
    @Column(name = "board_tag", nullable = false, length = 12)
    private String boardTag;

    @Column(name = "state", nullable = false, length = 16)
    private String state;

    @Column(name = "since", nullable = false)
    private Instant since;

    @Column(name = "reason", nullable = false, length = 240)
    private String reason = "";

    @Column(name = "actor", nullable = false, length = 64)
    private String actor = "";

    @Column(name = "is_available", nullable = false)
    private boolean available = true;

    @OneToOne(fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(
            name = "board_tag",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_board_states_board_tag"))
    private BoardRow board;

    /** An empty row, filled in through its setters. */
    public BoardStateRow() {}

    public String getBoardTag() {
        return boardTag;
    }

    public void setBoardTag(String value) {
        boardTag = value;
    }

    public String getState() {
        return state;
    }

    public void setState(String value) {
        state = value;
    }

    public Instant getSince() {
        return since;
    }

    public void setSince(Instant value) {
        since = value;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String value) {
        reason = value;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String value) {
        actor = value;
    }

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean value) {
        available = value;
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
