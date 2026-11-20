package io.github.rkaushikdev.carrierworks.persistence;

import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.Records;
import io.github.rkaushikdev.carrierworks.jobs.ProgramJob;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Programming jobs. */
public final class JobRepository {

    private final EntityManager session;

    JobRepository(EntityManager session) {
        this.session = session;
    }

    /** The session this repository reads and writes through. */
    public EntityManager session() {
        return session;
    }

    /** Queue a new job to run as soon as it can. */
    public ProgramJob add(ProgramJob job) {
        return add(job, Optional.empty());
    }

    /** Queue a new job, not to run before a moment when there is one. */
    public ProgramJob add(ProgramJob job, Optional<Instant> notBefore) {
        session.persist(Mappers.jobToRow(job, notBefore));
        return job;
    }

    /** One job, or a not found. */
    public ProgramJob get(String jobId) {
        return Mappers.jobFromRow(row(jobId));
    }

    /** Write what became of a job, clearing any backoff. */
    public ProgramJob save(ProgramJob job) {
        return save(job, Optional.empty());
    }

    /** Write what became of a job, holding it back until a moment when there is one. */
    public ProgramJob save(ProgramJob job, Optional<Instant> notBefore) {
        Mappers.copyJob(job, notBefore, row(job.id()));
        return job;
    }

    /** Queued jobs whose backoff has passed, oldest first. */
    public List<ProgramJob> queued(Instant now) {
        return jobs(session.createQuery(
                        "select j from JobRow j where j.state = 'queued'"
                                + " and (j.notBefore is null or j.notBefore <= :now) order by j.createdAt, j.id",
                        JobRow.class)
                .setParameter("now", now)
                .getResultList());
    }

    /** Every job a board has had, oldest first. */
    public List<ProgramJob> forBoard(String tag) {
        return jobs(session.createQuery(
                        "select j from JobRow j where j.boardTag = :board order by j.createdAt", JobRow.class)
                .setParameter("board", Records.boardKey(tag))
                .getResultList());
    }

    /** Every job that loaded one image, oldest first. */
    public List<ProgramJob> forDigest(String digest) {
        return jobs(session.createQuery(
                        "select j from JobRow j where j.digest = :digest order by j.createdAt", JobRow.class)
                .setParameter("digest", digest)
                .getResultList());
    }

    /** Every job running now, the longest running first. */
    public List<ProgramJob> running() {
        return jobs(session.createQuery(
                        "select j from JobRow j where j.state = 'running' order by j.startedAt", JobRow.class)
                .getResultList());
    }

    private JobRow row(String jobId) {
        JobRow row = session.find(JobRow.class, jobId);
        if (row == null) {
            throw new NotFound("job", jobId);
        }
        return row;
    }

    private static List<ProgramJob> jobs(List<JobRow> rows) {
        return rows.stream().map(Mappers::jobFromRow).toList();
    }
}
