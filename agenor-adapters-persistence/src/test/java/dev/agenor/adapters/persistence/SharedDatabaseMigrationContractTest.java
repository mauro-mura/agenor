package dev.agenor.adapters.persistence;

import dev.agenor.adapters.persistence.directory.DirectorySchemaManager;
import dev.agenor.adapters.persistence.hitl.HitlSchemaManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Abstract contract test: the directory and the HITL queue migrated into one database, with each
 * other and with an application's own schema.
 *
 * <p>Up to 0.37.0 both schemas recorded themselves in Flyway's default history table and both
 * have a {@code V1}, so whichever ran second failed validation; and a baseline at version 1
 * skipped Agenor's {@code V1} on any database that already held a table. Subclasses supply a
 * fresh, empty database per test: {@code SharedDatabaseMigrationTest} on H2 for the normal
 * build, {@code SharedDatabaseMigrationIT} on PostgreSQL.
 */
public abstract class SharedDatabaseMigrationContractTest {

    private static final String DIRECTORY = "classpath:db/agenor/directory";
    private static final String HITL = "classpath:db/agenor/hitl";
    private static final String APP = "classpath:db/migration/test-app";

    /** Returns a data source on an empty database that no other test uses. */
    protected abstract DataSource freshDatabase();

    /** Releases what {@link #freshDatabase()} opened. */
    protected abstract void closeDatabase(DataSource dataSource);

    private DataSource dataSource;

    @BeforeEach
    void openDatabase() {
        dataSource = freshDatabase();
    }

    @AfterEach
    void releaseDatabase() {
        closeDatabase(dataSource);
    }

    @Test
    @DisplayName("directory then HITL on one database: both schemas exist")
    void directoryThenHitl() {
        migrateDirectory();
        migrateHitl();

        assertSchemasExist();
    }

    @Test
    @DisplayName("HITL then directory on one database: both schemas exist")
    void hitlThenDirectory() {
        // This is the order the Spring starter runs them in when HITL falls back to the
        // directory's URL
        migrateHitl();
        migrateDirectory();

        assertSchemasExist();
    }

    @Test
    @DisplayName("migrating again on every start changes nothing")
    void migratingAgainIsANoOp() throws SQLException {
        migrateDirectory();
        migrateHitl();
        insertAgent("survivor");

        migrateHitl();
        migrateDirectory();

        assertSchemasExist();
        assertThat(agentCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a 0.37.0 directory database keeps its rows and gains the HITL schema")
    void upgradesADirectoryDatabaseFromTheDefaultHistoryTable() throws SQLException {
        // Given a database migrated the way 0.37.0 did it, with a registered agent
        legacyMigrate(DIRECTORY);
        insertAgent("survivor");

        // When this version migrates both schemas into it
        migrateDirectory();
        migrateHitl();

        // Then nothing is recreated or lost, and HITL is there too
        assertSchemasExist();
        assertThat(agentCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a 0.37.0 HITL database gains the directory schema")
    void upgradesAHitlDatabaseFromTheDefaultHistoryTable() {
        legacyMigrate(HITL);

        migrateHitl();
        migrateDirectory();

        assertSchemasExist();
    }

    @Test
    @DisplayName("an application schema migrated first does not make the directory skip its V1")
    void applicationSchemaMigratedFirst() {
        // Given an application that migrated its own tables first, with its own history table
        Flyway.configure()
                .dataSource(dataSource)
                .table("app_schema_history")
                .locations(APP)
                .load()
                .migrate();

        // When Agenor migrates into the same, no longer empty, database
        migrateDirectory();
        migrateHitl();

        // Then its tables are created rather than baselined away
        assertSchemasExist();
        assertThat(tableExists("app_jobs")).isTrue();
    }

    @Test
    @DisplayName("a location from 0.37.0 still creates both schemas, from where they are now")
    void oldLocationsStillMigrate() {
        // Given callers that still pass the locations used up to 0.37.0, under db/migration
        new DirectorySchemaManager(dataSource, "classpath:db/migration/agenor-directory").migrate();
        new HitlSchemaManager(dataSource, "classpath:db/migration/agenor-hitl").migrate();

        // Then the tables exist: no location that resolves to nothing, and no silent no-op
        assertSchemasExist();
    }

    // -------------------------------------------------------------------------

    private void migrateDirectory() {
        new DirectorySchemaManager(dataSource, DIRECTORY).migrate();
    }

    private void migrateHitl() {
        new HitlSchemaManager(dataSource, HITL).migrate();
    }

    /** The Flyway configuration both schema managers used up to 0.37.0. */
    private void legacyMigrate(String location) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations(location)
                .baselineOnMigrate(true)
                .load()
                .migrate();
    }

    private void assertSchemasExist() {
        assertThat(tableExists("agenor_agents")).as("agenor_agents").isTrue();
        assertThat(tableExists("agenor_agent_capabilities")).as("agenor_agent_capabilities").isTrue();
        assertThat(tableExists("agenor_hitl_requests")).as("agenor_hitl_requests").isTrue();
    }

    private void insertAgent(String agentId) throws SQLException {
        try (var c = dataSource.getConnection();
             var s = c.prepareStatement("INSERT INTO agenor_agents (agent_id, agent_name, agent_type, "
                     + "status, node_id, endpoint_transport_type, registered_at, last_seen) "
                     + "VALUES (?, 'n', 't', 'RUNNING', 'node', 'local', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
            s.setString(1, agentId);
            s.executeUpdate();
        }
    }

    private int agentCount() throws SQLException {
        try (var c = dataSource.getConnection();
             var rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM agenor_agents")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private boolean tableExists(String name) {
        try (var c = dataSource.getConnection()) {
            var meta = c.getMetaData();
            for (var candidate : new String[]{name, name.toUpperCase(Locale.ROOT)}) {
                try (var rs = meta.getTables(c.getCatalog(), null, candidate, new String[]{"TABLE"})) {
                    if (rs.next()) return true;
                }
            }
            return false;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
