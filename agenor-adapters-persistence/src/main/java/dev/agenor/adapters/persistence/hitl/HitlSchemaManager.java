package dev.agenor.adapters.persistence.hitl;

import dev.agenor.adapters.persistence.SchemaMigrations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

/**
 * Runs Flyway migrations for the HITL approval queue schema on startup.
 *
 * <p>History is kept in {@code agenor_hitl_schema_history}, so the queue can share a database
 * with the agent directory and with an application's own schema; see {@link SchemaMigrations}.
 *
 * @since 0.23.0
 */
public final class HitlSchemaManager {

    private static final Logger log = LoggerFactory.getLogger(HitlSchemaManager.class);

    private final DataSource dataSource;
    private final String migrationLocation;

    /**
     * Creates a schema manager that will run Flyway migrations from the given location.
     *
     * @param dataSource        the JDBC data source; must not be null
     * @param migrationLocation Flyway location, e.g. {@code classpath:db/migration/agenor-hitl}
     */
    public HitlSchemaManager(DataSource dataSource, String migrationLocation) {
        this.dataSource = dataSource;
        this.migrationLocation = migrationLocation;
    }

    /**
     * Applies all pending Flyway migrations. Safe to call on every startup, on a database of
     * its own or shared with the directory and an application's schema.
     */
    public void migrate() {
        log.info("Running HITL schema migrations from {}", migrationLocation);
        int executed = SchemaMigrations.migrate(dataSource, migrationLocation,
                "agenor_hitl_schema_history", "agenor_hitl_requests");
        log.info("HITL schema migrations applied: {}", executed);
    }
}
