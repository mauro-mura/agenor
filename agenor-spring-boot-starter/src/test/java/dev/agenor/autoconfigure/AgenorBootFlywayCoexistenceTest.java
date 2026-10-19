package dev.agenor.autoconfigure;

import dev.agenor.adapters.persistence.directory.JdbcAgentDirectory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An application's own Spring Boot Flyway and Agenor's JDBC persistence in one database, with
 * Boot's defaults.
 *
 * <p>Two things used to stop such an application from starting. Agenor's migrations lived under
 * {@code db/migration}, which Boot's Flyway scans recursively, so it found their {@code V1}s next
 * to the application's and failed with "more than one migration with version 1". And the
 * starter migrated Agenor's tables before Boot's Flyway ran, so Boot found a non-empty schema
 * with no history table of its own and refused to migrate.
 */
@DisplayName("Auto-configuration — Spring Boot's Flyway next to Agenor's")
class AgenorBootFlywayCoexistenceTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                    FlywayAutoConfiguration.class, AgenorAutoConfiguration.class));

    /** Boot's datasource and both Agenor schemas on one database. */
    private static String[] sharedDatabase(String name) {
        var url = "jdbc:h2:mem:boot_flyway_" + name + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
        // One user for both pools: H2 fixes it when the first connection creates the database
        return new String[]{
                "spring.datasource.url=" + url,
                "spring.datasource.username=sa",
                "agenor.directory.provider=jdbc",
                "agenor.directory.jdbc.url=" + url,
                "agenor.directory.jdbc.username=sa",
                "agenor.hitl.provider=jdbc"};
    }

    @Test
    @DisplayName("with Boot's defaults the application starts and every schema is migrated")
    void startsWithBootDefaults() {
        // The application's V1 sits in Boot's default location, classpath:db/migration
        runner.withPropertyValues(sharedDatabase("defaults"))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertMigrated(ctx.getBean(DataSource.class));
                });
    }

    @Test
    @DisplayName("Boot's Flyway runs before Agenor's, so it never meets a non-empty schema")
    void bootFlywayRunsFirst() {
        // A location of the application's own, so only the order is under test
        runner.withPropertyValues(sharedDatabase("order"))
                .withPropertyValues("spring.flyway.locations=classpath:db/testapp")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertMigrated(ctx.getBean(DataSource.class));
                });
    }

    @Test
    @DisplayName("without spring-boot-sql on the classpath the JDBC directory still starts")
    void startsWithoutSpringBootSql() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgenorAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader("org.springframework.boot.sql"))
                .withPropertyValues("agenor.directory.provider=jdbc",
                        "agenor.directory.jdbc.url=jdbc:h2:mem:boot_flyway_nosql"
                                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(JdbcAgentDirectory.class);
                });
    }

    /** The application's table, Agenor's two, and one history table per schema. */
    private static void assertMigrated(DataSource dataSource) throws SQLException {
        for (var table : new String[]{"app_tickets", "flyway_schema_history",
                "agenor_agents", "agenor_directory_schema_history",
                "agenor_hitl_requests", "agenor_hitl_schema_history"}) {
            assertThat(tableExists(dataSource, table)).as("table %s", table).isTrue();
        }
    }

    /** Under both identifier cases: Flyway quotes the history table names it creates. */
    private static boolean tableExists(DataSource dataSource, String table) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            for (var name : new String[]{table, table.toUpperCase(Locale.ROOT)}) {
                try (var rs = connection.getMetaData().getTables(null, null, name,
                        new String[]{"TABLE"})) {
                    if (rs.next()) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
