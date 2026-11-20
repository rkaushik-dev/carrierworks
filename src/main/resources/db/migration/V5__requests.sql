-- A waiting list for boards nobody has free.

CREATE TABLE requests (
    id             VARCHAR(16)  NOT NULL,
    holder         VARCHAR(64)  NOT NULL,
    purpose        VARCHAR(120) NOT NULL,
    requirements   VARCHAR(500) DEFAULT '' NOT NULL,
    submitted_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    priority       INTEGER DEFAULT 5 NOT NULL,
    minutes        INTEGER DEFAULT 60 NOT NULL,
    preemptible    BOOLEAN DEFAULT TRUE NOT NULL,
    needs_hands    BOOLEAN DEFAULT FALSE NOT NULL,
    cancelled_at   TIMESTAMP(6) WITH TIME ZONE,
    satisfied_at   TIMESTAMP(6) WITH TIME ZONE,
    assigned_board VARCHAR(12) DEFAULT '' NOT NULL,
    CONSTRAINT pk_requests PRIMARY KEY (id),
    CONSTRAINT ck_requests_priority CHECK (priority BETWEEN 1 AND 9),
    CONSTRAINT ck_requests_minutes CHECK (minutes > 0)
);
CREATE INDEX ix_requests_open ON requests (satisfied_at, cancelled_at);
CREATE INDEX ix_requests_holder ON requests (holder);
