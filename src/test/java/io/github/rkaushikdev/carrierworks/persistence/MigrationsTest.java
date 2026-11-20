package io.github.rkaushikdev.carrierworks.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.carrierworks.BenchSettings;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

class MigrationsTest {

    private static final AtomicLong NAMES = new AtomicLong();

    private Engine migrated;

    @BeforeEach
    void open() {
        migrated = engine();
    }

    @AfterEach
    void close() {
        migrated.close();
    }

    private static Engine engine() {
        return Engine.build(BenchSettings.builder()
                .databaseUrl("jdbc:h2:mem:migrations-" + NAMES.incrementAndGet())
                .build());
    }

    /** The tables, columns and indexes a database holds, read through the driver's metadata. */
    private record Schema(Map<String, Map<String, String>> columns, Map<String, Map<String, Boolean>> indexes) {

        static Schema of(DataSource source) {
            Map<String, Map<String, String>> columns = new TreeMap<>();
            Map<String, Map<String, Boolean>> indexes = new TreeMap<>();
            try (Connection connection = source.getConnection()) {
                DatabaseMetaData metadata = connection.getMetaData();
                try (ResultSet tables = metadata.getTables(null, "PUBLIC", "%", new String[] {"TABLE"})) {
                    while (tables.next()) {
                        String table = tables.getString("TABLE_NAME").toLowerCase(Locale.ROOT);
                        if (!table.equals("flyway_schema_history")) {
                            columns.put(table, new TreeMap<>());
                            indexes.put(table, new TreeMap<>());
                        }
                    }
                }
                for (String table : columns.keySet()) {
                    String upper = table.toUpperCase(Locale.ROOT);
                    try (ResultSet found = metadata.getColumns(null, "PUBLIC", upper, "%")) {
                        while (found.next()) {
                            columns.get(table)
                                    .put(
                                            found.getString("COLUMN_NAME").toLowerCase(Locale.ROOT),
                                            found.getString("TYPE_NAME") + "(" + found.getInt("COLUMN_SIZE") + ") "
                                                    + found.getString("IS_NULLABLE"));
                        }
                    }
                    try (ResultSet found = metadata.getIndexInfo(null, "PUBLIC", upper, false, false)) {
                        while (found.next()) {
                            String name = found.getString("INDEX_NAME").toLowerCase(Locale.ROOT);
                            if (name.startsWith("ix_") || name.startsWith("uq_leases")) {
                                indexes.get(table).put(name, !found.getBoolean("NON_UNIQUE"));
                            }
                        }
                    }
                }
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
            return new Schema(columns, indexes);
        }

        Set<String> tables() {
            return columns.keySet();
        }
    }

    /** A schema built straight from the row classes, with nothing the migrations wrote. */
    private static Schema fromTheMapping() {
        try (Engine bare = engine()) {
            LocalContainerEntityManagerFactoryBean bean = Engine.factoryBean(bare.dataSource(), "create");
            bean.afterPropertiesSet();
            try {
                return Schema.of(bare.dataSource());
            } finally {
                bean.destroy();
            }
        }
    }

    @Test
    void theBaselineBuildsEveryTable() {
        migrated.createSchema();
        assertThat(Schema.of(migrated.dataSource()).tables())
                .containsAll(fromTheMapping().tables());
    }

    @Test
    void theMigrationAndTheModelsAgreeOnColumns() {
        migrated.createSchema();
        Schema mapping = fromTheMapping();
        Schema schema = Schema.of(migrated.dataSource());
        assertThat(mapping.tables()).hasSize(13);
        for (String table : mapping.tables()) {
            assertThat(schema.columns().get(table))
                    .as(table)
                    .isEqualTo(mapping.columns().get(table));
        }
    }

    @Test
    void theIndexesTheQueriesDependOnAreThere() {
        migrated.createSchema();
        Schema schema = Schema.of(migrated.dataSource());
        assertThat(schema.indexes().get("leases")).containsKey("uq_leases_active_board");
        assertThat(schema.indexes().get("jobs")).containsKey("ix_jobs_board_state");
    }

    @Test
    void theOneOpenLeaseIndexIsUnique() {
        migrated.createSchema();
        assertThat(Schema.of(migrated.dataSource()).indexes().get("leases"))
                .containsEntry("uq_leases_active_board", true)
                .containsEntry("ix_leases_holder", false);
    }

    @Test
    void theDowngradeLeavesNothingBehind() {
        migrated.createSchema();
        migrated.dropSchema();
        assertThat(Schema.of(migrated.dataSource()).tables()).isEmpty();
    }

    @Test
    void theMigrationRunsTwiceWithoutComplaint() {
        migrated.createSchema();
        migrated.createSchema();
        assertThat(Schema.of(migrated.dataSource()).tables()).contains("boards");
    }

    /** The stamp is the newest migration, whichever that is today. */
    @Test
    void theVersionIsStampedAtTheLatestRevision() {
        migrated.createSchema();
        Flyway flyway = migrated.flyway();
        MigrationVersion latest =
                flyway.info().all()[flyway.info().all().length - 1].getVersion();
        assertThat(flyway.info().current().getVersion()).isEqualTo(latest);
        assertThat(latest.getVersion()).isEqualTo("6");
    }

    /** A site part way up the history upgrades the rest of the way. */
    @Test
    void theBaselineCanBeStoppedAtAndThenCarriedOn() {
        Flyway.configure()
                .dataSource(migrated.dataSource())
                .locations(Engine.MIGRATIONS)
                .target("1")
                .load()
                .migrate();
        assertThat(Schema.of(migrated.dataSource()).tables()).doesNotContain("api_keys");
        migrated.createSchema();
        assertThat(Schema.of(migrated.dataSource()).tables()).contains("api_keys");
    }

    @Test
    void theComparisonNoticesWhatItIsThereToNotice() throws SQLException {
        migrated.createSchema();
        Schema before = Schema.of(migrated.dataSource());
        assertThat(before.columns().values())
                .allSatisfy(columns -> assertThat(columns).isNotEmpty());
        assertThat(before.indexes().get("jobs")).isNotEmpty();
        try (Connection connection = migrated.dataSource().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE boards ADD COLUMN planted INTEGER");
            statement.execute("ALTER TABLE jobs ALTER COLUMN error VARCHAR(10)");
            statement.execute("CREATE INDEX ix_jobs_planted ON jobs (mode)");
        }
        Schema after = Schema.of(migrated.dataSource());
        Schema mapping = fromTheMapping();
        assertThat(after.columns().get("boards")).isNotEqualTo(mapping.columns().get("boards"));
        assertThat(after.columns().get("jobs")).isNotEqualTo(mapping.columns().get("jobs"));
        assertThat(after.indexes().get("jobs")).isNotEqualTo(before.indexes().get("jobs"));
        assertThat(after.columns().get("leases")).isEqualTo(mapping.columns().get("leases"));
        assertThat(new TreeSet<>(after.tables())).isEqualTo(new TreeSet<>(mapping.tables()));
    }
}
