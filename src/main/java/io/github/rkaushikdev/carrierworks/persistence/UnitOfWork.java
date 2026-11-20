package io.github.rkaushikdev.carrierworks.persistence;

import io.github.rkaushikdev.carrierworks.Conflict;
import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.ValidationError;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.PersistenceException;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.hibernate.exception.ConstraintViolationException;

/**
 * One session, one transaction, and every repository hanging off it.
 *
 * <p>A programming job touches the board, its state, its lease and the job row, and those four
 * either all land or none of them do. The unit of work is what makes that sentence true without
 * every service knowing about sessions.
 *
 * <p>It is deliberately not clever. {@link #apply} opens it, hands it to the work, commits when the
 * work returns unless it was already committed or rolled back, rolls back when the work throws, and
 * closes it either way. Outside that call its repositories refuse to be used, because the
 * alternative is a service quietly writing into a dead session and wondering where its rows went.
 * A unit can be opened again once it is closed.
 */
public final class UnitOfWork {

    private static final int REASON_LIMIT = 160;

    private final Supplier<EntityManager> sessions;
    private EntityManager session;
    private boolean committed;
    private boolean rolledBack;

    UnitOfWork(Supplier<EntityManager> sessions) {
        this.sessions = sessions;
    }

    /** Open the unit, run the work in it, and commit unless something went wrong. */
    public <T> T apply(Function<? super UnitOfWork, ? extends T> work) {
        if (session != null) {
            throw new ValidationError("this unit of work is already open", "session");
        }
        try {
            session = sessions.get();
            committed = false;
            rolledBack = false;
            session.getTransaction().begin();
            T result = work.apply(this);
            if (!committed && !rolledBack) {
                commit();
            }
            return result;
        } catch (RuntimeException failure) {
            if (session != null) {
                rolledBack = true;
                endTransaction(session);
            }
            throw translate(failure);
        } finally {
            if (session != null) {
                // Anything written after an explicit commit or rollback is not kept.
                endTransaction(session);
                session.close();
            }
            session = null;
        }
    }

    private static void endTransaction(EntityManager open) {
        if (open.getTransaction().isActive()) {
            open.getTransaction().rollback();
        }
    }

    /** {@link #apply} for work that hands nothing back. */
    public void run(Consumer<? super UnitOfWork> work) {
        apply(unit -> {
            work.accept(unit);
            return null;
        });
    }

    /** The open session, or a refusal when the unit is not open. */
    public EntityManager session() {
        if (session == null) {
            throw new ValidationError("this unit of work is not open", "session");
        }
        return session;
    }

    /** Whether the unit committed since it was opened. */
    public boolean committed() {
        return committed;
    }

    /** Whether the unit was rolled back since it was opened. */
    public boolean rolledBack() {
        return rolledBack;
    }

    public BoardRepository boards() {
        return new BoardRepository(session());
    }

    public LeaseRepository leases() {
        return new LeaseRepository(session());
    }

    public JobRepository jobs() {
        return new JobRepository(session());
    }

    public ArtifactRepository artifacts() {
        return new ArtifactRepository(session());
    }

    public ProvenanceRepository provenance() {
        return new ProvenanceRepository(session());
    }

    public ApiKeyRepository apiKeys() {
        return new ApiKeyRepository(session());
    }

    public AuditRepository audit() {
        return new AuditRepository(session());
    }

    public BuildReportRepository reports() {
        return new BuildReportRepository(session());
    }

    public MaintenanceRepository maintenance() {
        return new MaintenanceRepository(session());
    }

    public RequestRepository requests() {
        return new RequestRepository(session());
    }

    public ThermalRepository thermal() {
        return new ThermalRepository(session());
    }

    /**
     * Commit, turning a constraint violation into a conflict.
     *
     * <p>The database is the last line of defence for the invariants the bench rests on, and when
     * it fires the caller has raced somebody else rather than asked for something silly. That is a
     * conflict, and saying so here keeps the persistence provider out of every layer above this
     * one. The session carries on in a fresh transaction afterwards.
     */
    public void commit() {
        EntityTransaction transaction = session().getTransaction();
        try {
            transaction.commit();
        } catch (PersistenceException failure) {
            rollback();
            throw conflict(failure).orElseThrow(() -> failure);
        }
        committed = true;
        transaction.begin();
    }

    /** Throw away everything written since the last commit. The session carries on, emptied. */
    public void rollback() {
        EntityManager open = session();
        if (open.getTransaction().isActive()) {
            open.getTransaction().rollback();
        }
        open.clear();
        rolledBack = true;
        open.getTransaction().begin();
    }

    /**
     * Push the writes out without ending the transaction.
     *
     * <p>Needed where a later step reads something an earlier one wrote, which the board slot
     * rebuild does.
     */
    public void flush() {
        session().flush();
    }

    /**
     * A constraint violation raised anywhere in the work is a conflict, and a database that
     * cannot answer is a configuration error; anything else goes up as it is.
     */
    private static RuntimeException translate(RuntimeException failure) {
        if (!(failure instanceof PersistenceException persistence)) {
            return failure;
        }
        Optional<Conflict> clash = conflict(persistence);
        if (clash.isPresent()) {
            return clash.get();
        }
        return Engine.unusable(persistence)
                .<RuntimeException>map(error -> error)
                .orElse(failure);
    }

    private static Optional<Conflict> conflict(PersistenceException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return Optional.of(new Conflict(
                        "that write collided with another", Details.of("detail", reason(violation)), failure));
            }
        }
        return Optional.empty();
    }

    /** The constraint out of a driver message, for the error details. */
    private static String reason(ConstraintViolationException violation) {
        Throwable driver = violation.getSQLException() == null ? violation : violation.getSQLException();
        String text = String.valueOf(driver.getMessage()).strip();
        String line = text.lines().findFirst().orElse("");
        int statement = line.indexOf("; SQL statement:");
        if (statement >= 0) {
            line = line.substring(0, statement);
        }
        return line.length() > REASON_LIMIT ? line.substring(0, REASON_LIMIT) : line;
    }
}
