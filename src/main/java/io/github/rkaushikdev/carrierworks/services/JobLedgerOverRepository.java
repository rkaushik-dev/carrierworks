package io.github.rkaushikdev.carrierworks.services;

import io.github.rkaushikdev.carrierworks.JobLedger;
import io.github.rkaushikdev.carrierworks.jobs.ProgramJob;
import io.github.rkaushikdev.carrierworks.persistence.UnitOfWork;
import java.util.Objects;

/** The two job methods recovery needs, backed by the job repository. */
public final class JobLedgerOverRepository implements JobLedger {

    private final UnitOfWork unit;

    /** A job ledger reading and writing through this unit of work. */
    public JobLedgerOverRepository(UnitOfWork unit) {
        this.unit = Objects.requireNonNull(unit, "unit");
    }

    @Override
    public ProgramJob job(String jobId) {
        return unit.jobs().get(jobId);
    }

    @Override
    public ProgramJob update(ProgramJob job) {
        return unit.jobs().save(job);
    }
}
