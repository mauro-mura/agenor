-- Agent directory schema — see ADR-023

CREATE TABLE agenor_agents (
    agent_id                VARCHAR(255)  NOT NULL,
    agent_name              VARCHAR(255)  NOT NULL,
    agent_type              VARCHAR(255)  NOT NULL,
    status                  VARCHAR(50)   NOT NULL,
    node_id                 VARCHAR(255)  NOT NULL,
    endpoint_transport_type VARCHAR(100)  NOT NULL,
    endpoint_props          TEXT,
    metadata                TEXT,
    registered_at           TIMESTAMP     NOT NULL,
    last_seen               TIMESTAMP     NOT NULL,
    CONSTRAINT pk_agenor_agents PRIMARY KEY (agent_id)
);

CREATE TABLE agenor_agent_capabilities (
    agent_id    VARCHAR(255)  NOT NULL,
    capability  VARCHAR(255)  NOT NULL,
    CONSTRAINT pk_agenor_agent_capabilities PRIMARY KEY (agent_id, capability),
    CONSTRAINT fk_agenor_agent_capabilities_agent
        FOREIGN KEY (agent_id) REFERENCES agenor_agents(agent_id) ON DELETE CASCADE
);

CREATE INDEX idx_agenor_agents_status ON agenor_agents(status);
CREATE INDEX idx_agenor_agents_type   ON agenor_agents(agent_type);
CREATE INDEX idx_agenor_agents_node   ON agenor_agents(node_id);
