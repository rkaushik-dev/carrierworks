package io.github.rkaushikdev.carrierworks.api;

import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.TestClock;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The service as a test runs it: started the way {@code fpga serve} starts it, on a port of its
 * own, over an in-memory database of its own and a clock the test sets.
 *
 * <p>Starting the service costs seconds, so a test class starts one and {@link #reset} empties its
 * database before each test, which gives every test the bench a fresh database would.
 */
public final class TestBench implements AutoCloseable {

    private final BenchDatabase database;
    private final BenchSettings settings;
    private final TestClock clock;
    private final ConfigurableApplicationContext context;
    private final Http http;

    private TestBench(BenchSettings settings, Instant now, List<Class<?>> routes) {
        this.database = BenchDatabase.inMemory();
        this.settings = settings;
        this.clock = new TestClock(now);
        this.context = BenchApplication.run(database, settings, clock, routes, "--server.port=0");
        this.http = new Http(
                MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                        .build(),
                settings.apiKeyHeader());
    }

    /** The service with these settings, the clock stopped at {@code now}, and any extra routes. */
    public static TestBench start(BenchSettings settings, Instant now, Class<?>... routes) {
        return new TestBench(settings, now, List.of(routes));
    }

    public BenchDatabase database() {
        return database;
    }

    public BenchSettings settings() {
        return settings;
    }

    public TestClock clock() {
        return clock;
    }

    public ConfigurableApplicationContext context() {
        return context;
    }

    /** A client presenting no key. */
    public Http http() {
        return http;
    }

    /** Empty every table and put the clock back, as if the database had just been made. */
    public void reset(Instant now) {
        clock.set(now);
        try (Connection connection = database.engine().dataSource().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SET REFERENTIAL_INTEGRITY FALSE");
            for (String table : tables(connection.getMetaData())) {
                statement.execute("TRUNCATE TABLE " + table + " RESTART IDENTITY");
            }
            statement.execute("SET REFERENTIAL_INTEGRITY TRUE");
        } catch (SQLException failure) {
            throw new IllegalStateException("could not empty the test database", failure);
        }
    }

    private static List<String> tables(DatabaseMetaData metadata) throws SQLException {
        List<String> names = new ArrayList<>();
        try (ResultSet tables = metadata.getTables(null, "PUBLIC", "%", new String[] {"TABLE"})) {
            while (tables.next()) {
                String name = tables.getString("TABLE_NAME").toLowerCase(Locale.ROOT);
                if (!name.equals("flyway_schema_history")) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    @Override
    public void close() {
        context.close();
        database.close();
    }
}
