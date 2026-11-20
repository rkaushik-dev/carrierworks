package io.github.rkaushikdev.carrierworks.persistence;

import io.github.rkaushikdev.carrierworks.AssetTag;
import io.github.rkaushikdev.carrierworks.BenchLocation;
import io.github.rkaushikdev.carrierworks.PduChannel;
import io.github.rkaushikdev.carrierworks.artifacts.ArtifactKind;
import io.github.rkaushikdev.carrierworks.artifacts.ArtifactRecord;
import io.github.rkaushikdev.carrierworks.catalog.PartNumber;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardSlot;
import io.github.rkaushikdev.carrierworks.inventory.BoardState;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.jobs.JobMode;
import io.github.rkaushikdev.carrierworks.jobs.JobState;
import io.github.rkaushikdev.carrierworks.jobs.ProgramJob;
import io.github.rkaushikdev.carrierworks.jtag.AdapterUnit;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Between the objects the bench works in and the rows the database keeps.
 *
 * <p>Written by hand, both directions, one method each way. The alternative is hanging mapping
 * annotations off the domain records, and then every package that touches a board drags in the
 * persistence provider and every test needs a database.
 */
public final class Mappers {

    private Mappers() {}

    /** A new row for a board, its slots hung off it. */
    public static BoardRow boardToRow(Board board) {
        BoardRow row = new BoardRow();
        row.setTag(board.tag());
        copyBoard(board, row);
        for (BoardSlot slot : board.slots()) {
            row.addSlot(slotToRow(slot));
        }
        return row;
    }

    /** Copy every column of a board onto a row, leaving its slots alone. */
    static void copyBoard(Board board, BoardRow row) {
        row.setModel(board.model());
        row.setRevision(board.revision());
        row.setPart(board.part().orderingCode().toLowerCase(Locale.ROOT));
        row.setDevice(board.device().device());
        row.setFlash(board.flash());
        row.setRack(board.location().rack());
        row.setShelf(board.location().shelf());
        row.setSlot(board.location().slot());
        row.setAdapterSerial(board.adapter().map(AdapterUnit::serial).orElse(null));
        row.setAdapterModel(board.adapter().map(AdapterUnit::model).orElse(null));
        row.setAdapterPort(board.adapter().map(AdapterUnit::port).orElse(""));
        row.setPdu(board.pdu().map(PduChannel::pdu).orElse(null));
        row.setOutlet(board.pdu().map(PduChannel::outlet).orElse(null));
        row.setVadjMillivolts(board.vadjMillivolts());
        row.setHeaderVoltageMv(board.headerVoltageMv());
        row.setMaxTckHz(board.maxTckHz());
        row.setNotes(board.notes());
    }

    /** A new row for one connector. */
    static SlotRow slotToRow(BoardSlot slot) {
        SlotRow row = new SlotRow();
        row.setName(slot.name());
        row.setConnector(slot.connector());
        row.setFitted(slot.fitted().orElse(null));
        return row;
    }

    /** The board a row describes, checked again on the way out. */
    public static Board boardFromRow(BoardRow row) {
        Optional<AdapterUnit> adapter = present(row.getAdapterSerial()) && present(row.getAdapterModel())
                ? Optional.of(new AdapterUnit(row.getAdapterSerial(), row.getAdapterModel(), row.getAdapterPort()))
                : Optional.empty();
        Optional<PduChannel> pdu = present(row.getPdu()) && row.getOutlet() != null
                ? Optional.of(new PduChannel(row.getPdu(), row.getOutlet()))
                : Optional.empty();
        List<BoardSlot> slots = row.getSlots().stream()
                .map(slot -> new BoardSlot(slot.getName(), slot.getConnector(), Optional.ofNullable(slot.getFitted())))
                .toList();
        return new Board(
                AssetTag.parse(row.getTag()),
                row.getModel(),
                PartNumber.parse(row.getPart()),
                row.getFlash(),
                new BenchLocation(row.getRack(), row.getShelf(), row.getSlot()),
                row.getRevision(),
                slots,
                adapter,
                pdu,
                row.getVadjMillivolts(),
                row.getHeaderVoltageMv(),
                row.getMaxTckHz(),
                row.getNotes());
    }

    /** A new state row for a board. */
    public static BoardStateRow stateToRow(String tag, BoardState state) {
        BoardStateRow row = new BoardStateRow();
        row.setBoardTag(tag);
        copyState(state, row);
        return row;
    }

    /** Copy a state onto a row that already exists. */
    static void copyState(BoardState state, BoardStateRow row) {
        row.setState(state.state().value());
        row.setSince(state.since());
        row.setReason(state.reason());
        row.setActor(state.actor());
        row.setAvailable(state.usable());
    }

    /** The state a row holds. */
    public static BoardState stateFromRow(BoardStateRow row) {
        return new BoardState(BoardStatus.parse(row.getState()), row.getSince(), row.getReason(), row.getActor());
    }

    /** A new row for a lease. */
    public static LeaseRow leaseToRow(Lease lease) {
        LeaseRow row = new LeaseRow();
        row.setId(lease.id());
        row.setBoardTag(lease.board());
        row.setHolder(lease.holder());
        row.setPurpose(lease.purpose());
        row.setTakenAt(lease.takenAt());
        row.setExpiresAt(lease.expiresAt());
        row.setPriority(lease.priority());
        row.setRenewals(lease.renewals());
        row.setPreemptible(lease.preemptible());
        row.setReleasedAt(lease.releasedAt().orElse(null));
        row.setReleaseReason(lease.releaseReason());
        return row;
    }

    /** The lease a row holds. */
    public static Lease leaseFromRow(LeaseRow row) {
        Lease.Builder lease = Lease.builder(
                        row.getId(),
                        row.getBoardTag(),
                        row.getHolder(),
                        row.getPurpose(),
                        row.getTakenAt(),
                        row.getExpiresAt())
                .priority(row.getPriority())
                .renewals(row.getRenewals())
                .preemptible(row.isPreemptible())
                .releaseReason(row.getReleaseReason());
        if (row.getReleasedAt() != null) {
            lease.releasedAt(row.getReleasedAt());
        }
        return lease.build();
    }

    /** A new row for a job, with no backoff. */
    public static JobRow jobToRow(ProgramJob job) {
        return jobToRow(job, Optional.empty());
    }

    /** A new row for a job, not to be run before a moment when there is one. */
    public static JobRow jobToRow(ProgramJob job, Optional<Instant> notBefore) {
        JobRow row = new JobRow();
        row.setId(job.id());
        row.setBoardTag(job.board());
        row.setDigest(job.digest());
        row.setMode(job.mode().value());
        row.setRequestedBy(job.requestedBy());
        row.setCreatedAt(job.createdAt());
        copyJob(job, notBefore, row);
        return row;
    }

    /** Copy what can change about a job onto its row. */
    static void copyJob(ProgramJob job, Optional<Instant> notBefore, JobRow row) {
        row.setState(job.state().value());
        row.setAttempts(job.attempts());
        row.setVerify(job.verify());
        row.setStartedAt(job.startedAt().orElse(null));
        row.setFinishedAt(job.finishedAt().orElse(null));
        row.setNotBefore(notBefore.orElse(null));
        row.setError(job.error());
        row.setErrorCode(job.errorCode());
        row.setClocks(Math.toIntExact(job.clocks()));
        row.setLeaseId(job.lease().isEmpty() ? null : job.lease());
    }

    /** The job a row holds. */
    public static ProgramJob jobFromRow(JobRow row) {
        return ProgramJob.builder(
                        row.getId(),
                        row.getBoardTag(),
                        row.getDigest(),
                        JobMode.parse(row.getMode()),
                        row.getRequestedBy(),
                        row.getCreatedAt())
                .state(JobState.parse(row.getState()))
                .attempts(row.getAttempts())
                .verify(row.isVerify())
                .startedAt(Optional.ofNullable(row.getStartedAt()))
                .finishedAt(Optional.ofNullable(row.getFinishedAt()))
                .error(row.getError())
                .errorCode(row.getErrorCode())
                .clocks(row.getClocks())
                .lease(row.getLeaseId() == null ? "" : row.getLeaseId())
                .build();
    }

    /** A new row for a stored image, stamped with when it was stored. */
    public static ArtifactRow artifactToRow(ArtifactRecord record, Instant storedAt) {
        ArtifactRow row = new ArtifactRow();
        row.setDigest(record.digest());
        row.setDesign(record.design());
        row.setDevice(record.device());
        row.setLengthBytes(Math.toIntExact(record.length()));
        row.setKind(record.kind().value());
        row.setChannel(record.channel());
        row.setSource(record.source());
        row.setToolVersion(record.toolVersion());
        row.setStoredAt(storedAt);
        row.setTags(String.join(",", record.tags()));
        return row;
    }

    /** The record a row holds, its storage time back in the record's text form. */
    public static ArtifactRecord artifactFromRow(ArtifactRow row) {
        List<String> tags = Arrays.stream(row.getTags().split(",", -1))
                .filter(tag -> !tag.isEmpty())
                .toList();
        return new ArtifactRecord(
                row.getDigest(),
                row.getDesign(),
                row.getDevice(),
                row.getLengthBytes(),
                ArtifactKind.parse(row.getKind()),
                row.getChannel(),
                row.getSource(),
                row.getToolVersion(),
                Times.iso(row.getStoredAt(), Times.Precision.SECONDS),
                tags);
    }

    private static boolean present(String value) {
        return value != null && !value.isEmpty();
    }
}
