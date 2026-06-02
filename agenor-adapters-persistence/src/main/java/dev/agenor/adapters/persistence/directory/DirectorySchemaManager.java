package dev.agenor.adapters.persistence.directory;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

/**
 * Runs Flyway migrations for the agent directory schema on startup.
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
        var flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(migrationLocation)
                .baselineOnMigrate(true)
                .load();
        var result = flyway.migrate();
        log.info("Agent directory migrations applied: {}", result.migrationsExecuted);
    }
}
