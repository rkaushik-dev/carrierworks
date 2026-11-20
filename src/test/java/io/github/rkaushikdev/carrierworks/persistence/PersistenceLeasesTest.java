package io.github.rkaushikdev.carrierworks.persistence;

import static io.github.rkaushikdev.carrierworks.persistence.Constraints.refused;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PersistenceLeasesTest {

    private static final Instant NOW = Instant.parse("2025-11-21T08:00:00Z");
    private static final String DIGEST = "ab".repeat(32);

    private BenchDatabase database;
    private Engine engine;

    @BeforeEach
    void open() {
        database = BenchDatabase.inMemory();
        engine = database.engine();
        engine.sessionScope(session -> {
            BoardRow board = new BoardRow();
            board.setTag("bx-14");
            board.setModel("rx-carrier-b");
            board.setPart("xc7k325t-2ffg900i");
            board.setDevice("xc7k325t");
            board.setFlash("n25q128a13");
            board.setRack("rack-2");
            board.setShelf(4);
            board.setSlot(3);
            session.persist(board);
        });
    }

    @AfterEach
    void close() {
        database.close();
    }

    private static LeaseRow leaseRow() {
        return leaseRow("lease-00001", null, 5, 1);
    }

    private static LeaseRow leaseRow(String id, Instant released, int priority, int hours) {
        LeaseRow row = new LeaseRow();
        row.setId(id);
        row.setBoardTag("bx-14");
        row.setHolder("team-a");
        row.setPurpose("capture regression");
        row.setTakenAt(NOW);
        row.setExpiresAt(NOW.plus(Duration.ofHours(hours)));
        row.setPriority(priority);
        row.setReleasedAt(released);
        return row;
    }

    private static JobRow jobRow(String id, String digest, String state) {
        JobRow row = new JobRow();
        row.setId(id);
        row.setBoardTag("bx-14");
        row.setDigest(digest);
        row.setMode("jtag");
        row.setRequestedBy("team-a");
        row.setState(state);
        row.setCreatedAt(NOW);
        return row;
    }

    private static JobRow jobRow() {
        return jobRow("job-00001", DIGEST, "queued");
    }

    private long count(String entity) {
        long[] found = new long[1];
        engine.sessionScope(
                session -> found[0] = session.createQuery("select count(x) from " + entity + " x", Long.class)
                        .getSingleResult());
        return found[0];
    }

    @Test
    void aLeaseRoundTrips() {
        engine.sessionScope(session -> session.persist(leaseRow()));
        engine.sessionScope(session -> {
            LeaseRow found = session.find(LeaseRow.class, "lease-00001");
            assertThat(found.getHolder()).isEqualTo("team-a");
            assertThat(found.getPriority()).isEqualTo(5);
            assertThat(found.getReleasedAt()).isNull();
        });
    }

    @Test
    void oneBoardCannotHaveTwoOpenLeases() {
        engine.sessionScope(session -> session.persist(leaseRow()));
        refused(() -> engine.sessionScope(session -> session.persist(leaseRow("lease-00002", null, 5, 1))));
    }

    @Test
    void aClosedLeaseLetsTheNextOneIn() {
        engine.sessionScope(
                session -> session.persist(leaseRow("lease-00001", NOW.plus(Duration.ofMinutes(30)), 5, 1)));
        engine.sessionScope(session -> session.persist(leaseRow("lease-00002", null, 5, 1)));
        assertThat(count("LeaseRow")).isEqualTo(2);
    }

    @Test
    void anyNumberOfClosedLeasesAreAllowed() {
        engine.sessionScope(session -> {
            for (int index = 0; index < 4; index++) {
                session.persist(leaseRow("lease-" + index, NOW.plus(Duration.ofMinutes(index + 1)), 5, 1));
            }
        });
        assertThat(count("LeaseRow")).isEqualTo(4);
    }

    @Test
    void aPriorityOffTheScaleIsRefused() {
        refused(() -> engine.sessionScope(session -> session.persist(leaseRow("lease-00001", null, 42, 1))));
    }

    @Test
    void aLeaseThatExpiresBeforeItStartsIsRefused() {
        refused(() -> engine.sessionScope(session -> session.persist(leaseRow("lease-00001", null, 5, -1))));
    }

    @Test
    void aLeaseOnABoardThatIsNotThereIsRefused() {
        refused(() -> engine.sessionScope(session -> {
            LeaseRow row = leaseRow();
            row.setBoardTag("bx-99");
            session.persist(row);
        }));
    }

    @Test
    void aBoardHoldingALeaseCannotSimplyBeDeleted() {
        engine.sessionScope(session -> session.persist(leaseRow()));
        refused(() -> engine.sessionScope(session -> session.remove(session.find(BoardRow.class, "bx-14"))));
    }

    @Test
    void aJobRoundTrips() {
        engine.sessionScope(session -> session.persist(jobRow()));
        engine.sessionScope(session -> {
            JobRow found = session.find(JobRow.class, "job-00001");
            assertThat(found.getMode()).isEqualTo("jtag");
            assertThat(found.getAttempts()).isZero();
            assertThat(found.isVerify()).isTrue();
            assertThat(found.getNotBefore()).isNull();
        });
    }

    @Test
    void aJobWithAShortDigestIsRefused() {
        refused(() -> engine.sessionScope(session -> session.persist(jobRow("job-00001", "abcdef", "queued"))));
    }

    @Test
    void negativeAttemptsAreRefused() {
        refused(() -> engine.sessionScope(session -> {
            JobRow row = jobRow();
            row.setAttempts(-1);
            session.persist(row);
        }));
    }

    @Test
    void jobsAreFoundByBoardAndState() {
        engine.sessionScope(session -> {
            session.persist(jobRow());
            session.persist(jobRow("job-00002", DIGEST, "passed"));
        });
        engine.sessionScope(session -> assertThat(session.createQuery(
                                "select j from JobRow j where j.boardTag = 'bx-14' and j.state = 'queued'",
                                JobRow.class)
                        .getResultList())
                .extracting(JobRow::getId)
                .containsExactly("job-00001"));
    }

    @Test
    void jobsAreFoundByDigest() {
        engine.sessionScope(session -> session.persist(jobRow()));
        engine.sessionScope(session -> assertThat(
                        session.createQuery("select j from JobRow j where j.digest = :digest", JobRow.class)
                                .setParameter("digest", DIGEST)
                                .getResultList())
                .hasSize(1));
    }
}
