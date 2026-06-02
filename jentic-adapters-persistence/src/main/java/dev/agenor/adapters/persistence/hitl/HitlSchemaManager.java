package dev.agenor.adapters.persistence.hitl;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

/**
 * Runs Flyway migrations for the HITL approval queue schema on startup.
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
     * Applies all pending Flyway migrations. Idempotent — safe to call on every startup.
     */
    public void migrate() {
        log.info("Running HITL schema migrations from {}", migrationLocation);
        var flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(migrationLocation)
                .baselineOnMigrate(true)
                .load();
        var result = flyway.migrate();
        log.info("HITL schema migrations applied: {}", result.migrationsExecuted);
    }
}
