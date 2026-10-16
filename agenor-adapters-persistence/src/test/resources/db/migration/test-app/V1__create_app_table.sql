-- An application's own schema, migrated into the same database as Agenor's
CREATE TABLE app_jobs (
    job_id VARCHAR(255) NOT NULL,
    CONSTRAINT pk_app_jobs PRIMARY KEY (job_id)
);
