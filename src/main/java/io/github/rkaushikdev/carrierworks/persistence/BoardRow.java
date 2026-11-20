package io.github.rkaushikdev.carrierworks.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.hibernate.annotations.Check;

/**
 * One carrier in the rack.
 *
 * <p>Two rules the application already enforces are enforced here as well, on purpose. One board per
 * slot and one active lease per board are the invariants the whole bench rests on, and the second
 * writer, whenever there is one, will not be going through the application. A unique constraint costs
 * nothing and turns a race into an error.
 */
@Entity
@Table(
        name = "boards",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_boards_location",
                    columnNames = {"rack", "shelf", "slot"}),
            @UniqueConstraint(
                    name = "uq_boards_adapter",
                    columnNames = {"adapter_serial"}),
            @UniqueConstraint(
                    name = "uq_boards_outlet",
                    columnNames = {"pdu", "outlet"})
        },
        indexes = {
            @Index(name = "ix_boards_model", columnList = "model"),
            @Index(name = "ix_boards_device", columnList = "device")
        })
@Check(name = "ck_boards_shelf", constraints = "shelf between 1 and 12")
@Check(name = "ck_boards_slot", constraints = "slot between 1 and 6")
@Check(name = "ck_boards_tck", constraints = "max_tck_hz > 0")
public class BoardRow {

    @Id
    @Column(name = "tag", nullable = false, length = 12)
    private String tag;

    @Column(name = "model", nullable = false, length = 64)
    private String model;

    @Column(name = "revision", nullable = false, length = 8)
    private String revision = "a";

    @Column(name = "part", nullable = false, length = 32)
    private String part;

    @Column(name = "device", nullable = false, length = 16)
    private String device;

    @Column(name = "flash", nullable = false, length = 32)
    private String flash;

    @Column(name = "rack", nullable = false, length = 64)
    private String rack;

    @Column(name = "shelf", nullable = false)
    private int shelf;

    @Column(name = "slot", nullable = false)
    private int slot;

    @Column(name = "adapter_serial", length = 16)
    private String adapterSerial;

    @Column(name = "adapter_model", length = 64)
    private String adapterModel;

    @Column(name = "adapter_port", nullable = false, length = 64)
    private String adapterPort = "";

    @Column(name = "pdu", length = 64)
    private String pdu;

    @Column(name = "outlet")
    private Integer outlet;

    @Column(name = "vadj_millivolts", nullable = false)
    private int vadjMillivolts = 1800;

    @Column(name = "header_voltage_mv", nullable = false)
    private int headerVoltageMv = 1800;

    @Column(name = "max_tck_hz", nullable = false)
    private int maxTckHz = 30_000_000;

    @Column(name = "notes", nullable = false, length = 240)
    private String notes = "";

    @OneToMany(mappedBy = "board", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("name")
    private List<SlotRow> slots = new ArrayList<>();

    @OneToOne(mappedBy = "board", cascade = CascadeType.ALL, orphanRemoval = true)
    private BoardStateRow state;

    /** An empty row, filled in through its setters. */
    public BoardRow() {}

    public String getTag() {
        return tag;
    }

    public void setTag(String value) {
        tag = value;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String value) {
        model = value;
    }

    public String getRevision() {
        return revision;
    }

    public void setRevision(String value) {
        revision = value;
    }

    public String getPart() {
        return part;
    }

    public void setPart(String value) {
        part = value;
    }

    public String getDevice() {
        return device;
    }

    public void setDevice(String value) {
        device = value;
    }

    public String getFlash() {
        return flash;
    }

    public void setFlash(String value) {
        flash = value;
    }

    public String getRack() {
        return rack;
    }

    public void setRack(String value) {
        rack = value;
    }

    public int getShelf() {
        return shelf;
    }

    public void setShelf(int value) {
        shelf = value;
    }

    public int getSlot() {
        return slot;
    }

    public void setSlot(int value) {
        slot = value;
    }

    public String getAdapterSerial() {
        return adapterSerial;
    }

    public void setAdapterSerial(String value) {
        adapterSerial = value;
    }

    public String getAdapterModel() {
        return adapterModel;
    }

    public void setAdapterModel(String value) {
        adapterModel = value;
    }

    public String getAdapterPort() {
        return adapterPort;
    }

    public void setAdapterPort(String value) {
        adapterPort = value;
    }

    public String getPdu() {
        return pdu;
    }

    public void setPdu(String value) {
        pdu = value;
    }

    public Integer getOutlet() {
        return outlet;
    }

    public void setOutlet(Integer value) {
        outlet = value;
    }

    public int getVadjMillivolts() {
        return vadjMillivolts;
    }

    public void setVadjMillivolts(int value) {
        vadjMillivolts = value;
    }

    public int getHeaderVoltageMv() {
        return headerVoltageMv;
    }

    public void setHeaderVoltageMv(int value) {
        headerVoltageMv = value;
    }

    public int getMaxTckHz() {
        return maxTckHz;
    }

    public void setMaxTckHz(int value) {
        maxTckHz = value;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String value) {
        notes = value;
    }

    /** The board's connectors, by name. */
    public List<SlotRow> getSlots() {
        return Collections.unmodifiableList(slots);
    }

    /** Hang a connector off this board. */
    public void addSlot(SlotRow slot) {
        slot.attachTo(this);
        slots.add(slot);
    }

    /** Take every connector off this board; they are deleted with the next flush. */
    public void clearSlots() {
        slots.clear();
    }

    /** Where the board is, if a state has been recorded. */
    public Optional<BoardStateRow> getState() {
        return Optional.ofNullable(state);
    }

    /** Record the board's state, replacing whatever was there. */
    public void setState(BoardStateRow value) {
        value.attachTo(this);
        state = value;
    }
}
