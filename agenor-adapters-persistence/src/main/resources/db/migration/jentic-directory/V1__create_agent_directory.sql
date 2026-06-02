-- Agent directory schema — see ADR-023

CREATE TABLE jentic_agents (
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
    CONSTRAINT pk_jentic_agents PRIMARY KEY (agent_id)
);

CREATE TABLE jentic_agent_capabilities (
    agent_id    VARCHAR(255)  NOT NULL,
    capability  VARCHAR(255)  NOT NULL,
    CONSTRAINT pk_jentic_agent_capabilities PRIMARY KEY (agent_id, capability),
    CONSTRAINT fk_jentic_agent_capabilities_agent
        FOREIGN KEY (agent_id) REFERENCES jentic_agents(agent_id) ON DELETE CASCADE
);

CREATE INDEX idx_jentic_agents_status ON jentic_agents(status);
CREATE INDEX idx_jentic_agents_type   ON jentic_agents(agent_type);
CREATE INDEX idx_jentic_agents_node   ON jentic_agents(node_id);
