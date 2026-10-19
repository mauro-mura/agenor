-- HITL approval queue schema — see ADR-024

CREATE TABLE agenor_hitl_requests (
    request_id      VARCHAR(255) NOT NULL,
    agent_id        VARCHAR(255) NOT NULL,
    action          VARCHAR(255) NOT NULL,
    payload         TEXT,
    metadata        TEXT,
    created_at      TIMESTAMP    NOT NULL,
    expires_at      TIMESTAMP    NOT NULL,
    status          VARCHAR(50)  NOT NULL,
    decision_type   VARCHAR(50),
    decision_data   TEXT,
    decided_at      TIMESTAMP,
    decided_by      VARCHAR(255),
    CONSTRAINT pk_agenor_hitl_requests PRIMARY KEY (request_id)
);

CREATE INDEX idx_agenor_hitl_status     ON agenor_hitl_requests(status);
CREATE INDEX idx_agenor_hitl_agent      ON agenor_hitl_requests(agent_id);
CREATE INDEX idx_agenor_hitl_expires_at ON agenor_hitl_requests(expires_at);
