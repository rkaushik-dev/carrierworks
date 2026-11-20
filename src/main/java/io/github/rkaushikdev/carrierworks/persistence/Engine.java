package io.github.rkaushikdev.carrierworks.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.ConfigurationError;
import io.github.rkaushikdev.carrierworks.Details;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.hibernate.tool.schema.spi.SchemaManagementException;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/**
 * Getting a connection, and turning the one failure every fresh site hits into a sentence.
 *
 * <p>An engine is a connection pool for one database, the migrations that build its schema, and
 * the entity manager factory sessions are opened from. The factory is built the first time a
 * session is asked for and it checks the mapping against the schema when it is, so a database
 * nobody has migrated can still be migrated.
 *
 * <p>A database that has never been migrated answers every query with an error, and that error
 * would otherwise escape as a driver stack trace in front of whoever ran the command. It is the
 * single most likely thing to go wrong on a fresh site, so {@link #unusableDatabase} translates it
 * once, here, rather than in every caller.
 */
public final class Engine implements AutoCloseable {

    /** Where the row classes live. */
    public static final String MAPPED_PACKAGE = "io.github.rkaushikdev.carrierworks.persistence";

    /** Where the migrations live, one per revision of the schema. */
    public static final String MIGRATIONS = "classpath:db/migration";

    private final String url;
    private final HikariDataSource pool;
    private LocalContainerEntityManagerFactoryBean factoryBean;
    private EntityManagerFactory factory;

    private Engine(String url, HikariDataSource pool) {
        this.url = url;
        this.pool = pool;
    }

    /** An engine for the database the settings name. Nothing connects until something is asked. */
    public static Engine build(BenchSettings settings) {
        String url = settings.databaseUrl();
        return new Engine(url, pool(url));
    }

    /**
     * A pool for one process. An in-memory database lasts exactly as long as one connection to it
     * is open, so for one of those the pool keeps a connection for its whole life; anything else
     * connects only when first asked.
     */
    private static HikariDataSource pool(String url) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setPoolName("carrierworks");
        config.setMaximumPoolSize(4);
        config.setInitializationFailTimeout(-1);
        if (url.startsWith("jdbc:h2:mem:")) {
            config.setMinimumIdle(1);
            config.setMaxLifetime(0);
        } else {
            config.setMinimumIdle(0);
        }
        // A database that will not answer is reported within seconds, not after half a minute.
        config.setConnectionTimeout(3_000);
        return new HikariDataSource(config);
    }

    /**
     * The entity manager factory, mapping every row class.
     *
     * @param ddl what Hibernate does to the schema at start up: {@code validate} for a working
     *     database, {@code create} only to build a schema straight from the mapping for comparison
     */
    public static LocalContainerEntityManagerFactoryBean factoryBean(DataSource dataSource, String ddl) {
        LocalContainerEntityManagerFactoryBean bean = new LocalContainerEntityManagerFactoryBean();
        bean.setDataSource(dataSource);
        bean.setPackagesToScan(MAPPED_PACKAGE);
        bean.setPersistenceUnitName("carrierworks");
        bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("hibernate.hbm2ddl.auto", ddl);
        properties.put("hibernate.jdbc.time_zone", "UTC");
        // Hibernate otherwise lays columns out by type size, and a schema it builds stops matching
        // the order the migrations declare.
        properties.put("hibernate.column_ordering_strategy", "legacy");
        bean.setJpaPropertyMap(properties);
        return bean;
    }

    /** The URL as configured. */
    public String url() {
        return url;
    }

    /** The pool every session and migration draws from. */
    public DataSource dataSource() {
        return pool;
    }

    /** The factory, built and checked against the schema the first time it is asked for. */
    public synchronized EntityManagerFactory sessions() {
        if (factory == null) {
            LocalContainerEntityManagerFactoryBean bean = factoryBean(pool, "validate");
            try {
                bean.afterPropertiesSet();
            } catch (RuntimeException failure) {
                throw unusableDatabase(failure);
            }
            factoryBean = bean;
            factory = bean.getObject();
        }
        return factory;
    }

    /** Build every table, which is what the tests and a fresh site do. */
    public void createSchema() {
        flyway().migrate();
    }

    /** Take every table out again. */
    public void dropSchema() {
        closeFactory();
        flyway().clean();
    }

    /** The migrations, pointed at this database. */
    public Flyway flyway() {
        return Flyway.configure()
                .dataSource(pool)
                .locations(MIGRATIONS)
                .cleanDisabled(false)
                .load();
    }

    /**
     * A session that commits on the way out and rolls back on the way up.
     *
     * @throws RuntimeException whatever the work or the commit threw, after rolling back
     */
    public void sessionScope(Consumer<EntityManager> work) {
        EntityManager session = sessions().createEntityManager();
        EntityTransaction transaction = session.getTransaction();
        try {
            transaction.begin();
            work.accept(session);
            transaction.commit();
        } catch (RuntimeException failure) {
            if (transaction.isActive()) {
                transaction.rollback();
            }
            throw failure;
        } finally {
            session.close();
        }
    }

    /**
     * The bench error for a database that cannot answer a query at all, or nothing when the
     * failure is something else.
     *
     * <p>Two things reach here and they want different sentences. A database whose tables are
     * missing has not been migrated, and saying so is the whole answer. Anything else is a file
     * that cannot be opened, a server that is not there, or a lock nobody released, and the
     * driver's own first line says more about which than the bench can.
     */
    public static Optional<ConfigurationError> unusable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && unusableState(sql)) {
                return Optional.of(unusableDatabase(sql));
            }
            if (cause instanceof SchemaManagementException
                    && String.valueOf(cause.getMessage()).contains("missing table")) {
                return Optional.of(unusableDatabase(cause));
            }
        }
        return Optional.empty();
    }

    /** {@link #unusable} for a failure already known to be the database's. */
    public static ConfigurationError unusableDatabase(Throwable failure) {
        String detail = firstLine(failure);
        String lower = detail.toLowerCase(Locale.ROOT);
        if (lower.contains("no such table")
                || lower.contains("does not exist")
                || (lower.contains("table") && lower.contains("not found"))
                || lower.contains("missing table")) {
            return new ConfigurationError(
                    "that database has no schema yet, run fpga db upgrade",
                    Details.of("setting", "database_url", "detail", detail),
                    failure);
        }
        return new ConfigurationError(
                "that database could not be used", Details.of("setting", "database_url", "detail", detail), failure);
    }

    private static boolean unusableState(SQLException failure) {
        String state = failure.getSQLState();
        // 42S02 / 42S04 / 42102: the table is not there. 08xxx: the connection could not be made.
        return state != null && (state.startsWith("42S0") || state.equals("42102") || state.startsWith("08"))
                || failure.getErrorCode() == 90046
                || failure.getErrorCode() == 90020;
    }

    private static String firstLine(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            if (root instanceof SQLException) {
                break;
            }
            root = root.getCause();
        }
        String message = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        String line = message.strip().lines().findFirst().orElse("");
        int statement = line.indexOf("; SQL statement:");
        return statement < 0 ? line : line.substring(0, statement);
    }

    /** Close the factory and the pool; an in-memory database goes with its last connection. */
    @Override
    public synchronized void close() {
        closeFactory();
        pool.close();
    }

    private synchronized void closeFactory() {
        if (factoryBean != null) {
            factoryBean.destroy();
            factoryBean = null;
            factory = null;
        }
    }
}
