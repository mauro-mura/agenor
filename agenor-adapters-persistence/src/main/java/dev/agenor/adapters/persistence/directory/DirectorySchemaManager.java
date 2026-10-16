package dev.agenor.adapters.persistence.directory;

import dev.agenor.adapters.persistence.SchemaMigrations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

/**
 * Runs Flyway migrations for the agent directory schema on startup.
 *
 * <p>History is kept in {@code agenor_directory_schema_history}, so the directory can share a
 * database with the HITL queue and with an application's own schema; see {@link SchemaMigrations}.
 *
 * @since 0.22.0
 */
public final class DirectorySchemaManager {

    private static final Logger log = LoggerFactory.getLogger(DirectorySchemaManager.class);

    private final DataSource dataSource;
    private final String migrationLocation;

    public DirectorySchemaManager(DataSource dataSource, String migrationLocation) {
        this.dataSource = dataSource;
        this.migrationLocation = migrationLocation;
    }

    public void migrate() {
        log.info("Running agent directory schema migrations from {}", migrationLocation);
        int executed = SchemaMigrations.migrate(dataSource, migrationLocation,
                "agenor_directory_schema_history", "agenor_agents");
        log.info("Agent directory migrations applied: {}", executed);
    }
}
