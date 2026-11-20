package io.github.rkaushikdev.carrierworks.persistence;

import io.github.rkaushikdev.carrierworks.thermal.ThermalLog;
import io.github.rkaushikdev.carrierworks.thermal.ThermalSample;
import jakarta.persistence.EntityManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.hibernate.Session;

/** Temperature readings, written one at a time or in batches and read in windows. */
public final class ThermalRepository {

    private static final String INSERT =
            "insert into thermal_samples (board, at, millidegrees, source) values (?, ?, ?, ?)";

    private final EntityManager session;

    ThermalRepository(EntityManager session) {
        this.session = session;
    }

    /** The session this repository reads and writes through. */
    public EntityManager session() {
        return session;
    }

    /** Store one reading. */
    public ThermalSample record(ThermalSample sample) {
        session.persist(row(sample));
        return sample;
    }

    /**
     * Store a batch of readings in one statement, and say how many.
     *
     * <p>The rack controllers post a reading per board per poll, so a full rack is twenty four rows
     * every twenty seconds. Adding them one at a time is twenty four round trips to say one thing,
     * and this is the hottest write path the bench has.
     */
    public int recordMany(List<ThermalSample> samples) {
        if (samples.isEmpty()) {
            return 0;
        }
        session.flush();
        session.unwrap(Session.class).doWork(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(INSERT)) {
                for (ThermalSample sample : samples) {
                    insert.setString(1, sample.board());
                    insert.setObject(2, sample.at().atOffset(ZoneOffset.UTC));
                    insert.setInt(3, sample.millidegrees());
                    insert.setString(4, sample.source());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        });
        return samples.size();
    }

    /** The newest reading off one board, if it has any. */
    public Optional<ThermalSample> latest(String board) {
        return session.createQuery(
                        "select t from ThermalRow t where t.board = :board order by t.at desc, t.id desc",
                        ThermalRow.class)
                .setParameter("board", lowered(board))
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .map(ThermalRepository::sample);
    }

    /** Every reading at or after a moment, oldest first. */
    public List<ThermalSample> since(Instant when) {
        return session
                .createQuery("select t from ThermalRow t where t.at >= :when order by t.at, t.id", ThermalRow.class)
                .setParameter("when", when)
                .getResultList()
                .stream()
                .map(ThermalRepository::sample)
                .toList();
    }

    /** Every reading off one board at or after a moment, oldest first. */
    public List<ThermalSample> since(Instant when, String board) {
        return session
                .createQuery(
                        "select t from ThermalRow t where t.at >= :when and t.board = :board order by t.at, t.id",
                        ThermalRow.class)
                .setParameter("when", when)
                .setParameter("board", lowered(board))
                .getResultList()
                .stream()
                .map(ThermalRepository::sample)
                .toList();
    }

    /** {@link #load(Instant, int)} keeping the log's usual number of readings per board. */
    public ThermalLog load(Instant since) {
        return load(since, ThermalLog.DEFAULT_KEEP);
    }

    /**
     * Every reading since a moment, back in a log the supervisor can read.
     *
     * <p>The log is the same one the in-memory bench uses, so nothing above this class knows whether
     * the readings came from a database or from a poller that has been running for ten minutes.
     */
    public ThermalLog load(Instant since, int keep) {
        ThermalLog log = new ThermalLog(keep);
        since(since).forEach(log::record);
        return log;
    }

    /** Drop everything older than a moment, and say how many that was. */
    public int prune(Instant before) {
        List<ThermalRow> old = session.createQuery("select t from ThermalRow t where t.at < :before", ThermalRow.class)
                .setParameter("before", before)
                .getResultList();
        old.forEach(session::remove);
        return old.size();
    }

    /** How many readings there are. */
    public long count() {
        return session.createQuery("select count(t) from ThermalRow t", Long.class)
                .getSingleResult();
    }

    /** How many readings there are off one board. */
    public long count(String board) {
        return session.createQuery("select count(t) from ThermalRow t where t.board = :board", Long.class)
                .setParameter("board", lowered(board))
                .getSingleResult();
    }

    private static String lowered(String board) {
        return board.strip().toLowerCase(Locale.ROOT);
    }

    private static ThermalRow row(ThermalSample sample) {
        ThermalRow row = new ThermalRow();
        row.setBoard(sample.board());
        row.setAt(sample.at());
        row.setMillidegrees(sample.millidegrees());
        row.setSource(sample.source());
        return row;
    }

    private static ThermalSample sample(ThermalRow row) {
        return new ThermalSample(row.getBoard(), row.getAt(), row.getMillidegrees(), row.getSource());
    }
}
