package dev.agenor.adapters.persistence;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Internal Flyway runner shared by the directory and HITL schema managers.
 * Not public API — not to be referenced from outside this module.
 *
 * <p>Each schema records itself in a history table of its own, so the directory, the HITL queue
 * and an application's own migrations can share one database. Up to 0.37.0 both schemas used
 * Flyway's default table, both have a {@code V1}, and whichever migrated second failed validation.
 *
 * <p>The baseline is chosen from the schema's own tables, not from whether the database is empty.
 * {@code baselineOnMigrate} at Flyway's default version 1 skips {@code V1} on any database that
 * holds some other table, which left {@code agenor_agents} missing with no error. So, when the
 * history table does not exist yet:
 * <ul>
 *   <li>the marker table exists → baseline {@code 1}: a database migrated by 0.37.0 or earlier,
 *       whose history is in the default table. Only {@code V1} existed then, so it is at
 *       {@code V1} at most, and any later version still runs;</li>
 *   <li>otherwise → baseline {@code 0}, and {@code V1} runs whatever else the database holds.</li>
 * </ul>
 * Once the history table exists the baseline plays no part.
 *
 * <p>The migrations live under {@code db/agenor/}. Up to 0.37.0 they were under
 * {@code db/migration/}, which Spring Boot's Flyway scans recursively by default, so an
 * application with Flyway of its own found Agenor's {@code V1}s next to its own and failed with
 * "more than one migration with version 1". A caller still passing an old location is moved to
 * the new one with a warning, until {@code 1.0.0}.
 *
 * @since 0.38.0
 */
public final class SchemaMigrations {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrations.class);

    /** Locations used up to 0.37.0, and where the same files are now. */
    private static final Map<String, String> MOVED_LOCATIONS = Map.of(
            "db/migration/agenor-directory", "classpath:db/agenor/directory",
            "db/migration/agenor-hitl", "classpath:db/agenor/hitl");

    private static final Set<String> warned = ConcurrentHashMap.newKeySet();

    private SchemaMigrations() {}

    /**
     * Applies the pending migrations at {@code location}, recording them in {@code historyTable}.
     *
     * @param dataSource   the database to migrate
     * @param location     the Flyway location holding this schema's migrations
     * @param historyTable this schema's own history table
     * @param markerTable  a table {@code V1} creates, whose presence means {@code V1} has run
     * @return the number of migrations executed
     */
    public static int migrate(DataSource dataSource, String location,
                              String historyTable, String markerTable) {
        location = currentLocation(location);
        var config = Flyway.configure()
                .dataSource(dataSource)
                .locations(location)
                .table(historyTable)
                .baselineOnMigrate(true);
        try (var connection = dataSource.getConnection()) {
            if (!tableExists(connection, historyTable)) {
                var baseline = tableExists(connection, markerTable) ? "1" : "0";
                log.info("No {} yet; {} {} → baseline at version {}", historyTable, markerTable,
                        "1".equals(baseline) ? "exists" : "does not exist", baseline);
                config.baselineVersion(baseline);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not inspect the schema before migrating " + location, e);
        }
        return config.load().migrate().migrationsExecuted;
    }

    /**
     * Maps a location used up to 0.37.0 to where its migrations are now, warning once per
     * location; any other location is returned as it is.
     *
     * @param location the Flyway location a caller passed
     * @return the location to migrate from
     */
    static String currentLocation(String location) {
        var path = location.startsWith("classpath:")
                ? location.substring("classpath:".length()) : location;
        var moved = MOVED_LOCATIONS.get(path.replaceAll("^/+|/+$", ""));
        if (moved == null) {
            return location;
        }
        if (warned.add(location)) {
            log.warn("Flyway location {} moved to {} in 0.38.0; pass the new one. "
                    + "The old location will stop working in 1.0.0", location, moved);
        }
        return moved;
    }

    /** Looks in the connection's own schema, as Flyway does, under both identifier cases. */
    private static boolean tableExists(Connection connection, String table) throws SQLException {
        var meta = connection.getMetaData();
        for (var name : new String[]{table, table.toUpperCase(Locale.ROOT)}) {
            try (var rs = meta.getTables(connection.getCatalog(), connection.getSchema(), name,
                    new String[]{"TABLE"})) {
                if (rs.next()) {
                    return true;
                }
            }
        }
        return false;
    }
}
