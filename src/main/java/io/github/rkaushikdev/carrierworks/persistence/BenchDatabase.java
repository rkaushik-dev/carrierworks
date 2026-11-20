package io.github.rkaushikdev.carrierworks.persistence;

import io.github.rkaushikdev.carrierworks.BenchSettings;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The database as one object: the engine, the schema and units of work.
 *
 * <p>Everything above the persistence package works on plain objects and knows nothing about
 * SQL. That is what makes the rest of the bench testable without a database, and it is why the
 * mappers are written by hand rather than by hanging mapping annotations off the domain records.
 */
public final class BenchDatabase implements AutoCloseable {

    private static final AtomicLong IN_MEMORY = new AtomicLong();

    private final BenchSettings settings;
    private final Engine engine;

    /** The database the settings name. Nothing connects until something is asked. */
    public BenchDatabase(BenchSettings settings) {
        this.settings = settings;
        this.engine = Engine.build(settings);
    }

    /** The database the default settings name. */
    public BenchDatabase() {
        this(BenchSettings.defaults());
    }

    /**
     * A database that exists for as long as this object is open, schema and all.
     *
     * <p>Used by the tests and by anything that builds a whole bench, answers a question and
     * throws it away.
     */
    public static BenchDatabase inMemory() {
        BenchSettings settings = BenchSettings.builder()
                .databaseUrl("jdbc:h2:mem:bench-" + IN_MEMORY.incrementAndGet())
                .build();
        BenchDatabase database = new BenchDatabase(settings);
        database.createSchema();
        return database;
    }

    /** The settings this database was opened with. */
    public BenchSettings settings() {
        return settings;
    }

    /** The connection pool, migrations and session factory underneath. */
    public Engine engine() {
        return engine;
    }

    /** Build every table, or bring an older schema up to date. */
    public void createSchema() {
        engine.createSchema();
    }

    /** A unit of work, not yet open. */
    public UnitOfWork unit() {
        return new UnitOfWork(() -> engine.sessions().createEntityManager());
    }

    /** Run work in a fresh unit, committing unless it throws, and hand back what it returns. */
    public <T> T apply(Function<? super UnitOfWork, ? extends T> work) {
        return unit().apply(work);
    }

    /** Run work in a fresh unit, committing unless it throws. */
    public void run(Consumer<? super UnitOfWork> work) {
        unit().run(work);
    }

    /** Close every connection; an in-memory database is gone afterwards. */
    public void dispose() {
        engine.close();
    }

    @Override
    public void close() {
        dispose();
    }
}
