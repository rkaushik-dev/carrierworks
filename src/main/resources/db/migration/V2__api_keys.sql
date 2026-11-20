-- Credentials, stored as digests rather than as secrets.

CREATE TABLE api_keys (
    id            VARCHAR(32) NOT NULL,
    label         VARCHAR(64) NOT NULL,
    secret_digest VARCHAR(64) NOT NULL,
    role          VARCHAR(16) NOT NULL,
    team          VARCHAR(64) DEFAULT '' NOT NULL,
    created_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_used_at  TIMESTAMP(6) WITH TIME ZONE,
    active        BOOLEAN DEFAULT TRUE NOT NULL,
    CONSTRAINT pk_api_keys PRIMARY KEY (id),
    CONSTRAINT uq_api_keys_secret UNIQUE (secret_digest),
    CONSTRAINT ck_api_keys_digest CHECK (LENGTH(secret_digest) = 64)
);
CREATE INDEX ix_api_keys_role ON api_keys (role);
