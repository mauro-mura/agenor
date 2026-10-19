package dev.agenor.autoconfigure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AgenorBootFlywayCoexistenceTest}'s defaults case on PostgreSQL: an application's own
 * Spring Boot Flyway and Agenor's directory and HITL queue in one {@code public} schema, with
 * nothing configured beyond the URLs.
 *
 * <p>Enable with: {@code mvn verify -Dintegration.tests.enabled=true -pl agenor-spring-boot-starter}
 */
@Testcontainers
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
@EnabledIfDockerAvailable
@DisplayName("Starter — Spring Boot's Flyway next to Agenor's on PostgreSQL")
class StarterBootFlywayPostgresIT {

    @Container
    static GenericContainer<?> postgres = new GenericContainer<>("postgres:16-alpine")
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", "agenor_test")
            .withEnv("POSTGRES_USER", "agenor")
            .withEnv("POSTGRES_PASSWORD", "agenor_test")
            // The entrypoint runs a temporary server for initdb first; wait for the real one
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2))
            .withStartupTimeout(Duration.ofSeconds(60));

    @Test
    @DisplayName("with Boot's defaults the application starts and every schema is migrated")
    void startsWithBootDefaults() {
        var url = "jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(5432) + "/agenor_test";
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        FlywayAutoConfiguration.class, AgenorAutoConfiguration.class))
                .withPropertyValues(
                        "spring.datasource.url=" + url,
                        "spring.datasource.username=agenor",
                        "spring.datasource.password=agenor_test",
                        "agenor.directory.provider=jdbc",
                        "agenor.directory.jdbc.url=" + url,
                        "agenor.directory.jdbc.username=agenor",
                        "agenor.directory.jdbc.password=agenor_test",
                        "agenor.hitl.provider=jdbc")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    var dataSource = ctx.getBean(DataSource.class);
                    for (var table : new String[]{"app_tickets", "flyway_schema_history",
                            "agenor_agents", "agenor_directory_schema_history",
                            "agenor_hitl_requests", "agenor_hitl_schema_history"}) {
                        assertThat(tableExists(dataSource, table)).as("table %s", table).isTrue();
                    }
                });
    }

    private static boolean tableExists(DataSource dataSource, String table) throws SQLException {
        try (var connection = dataSource.getConnection();
             var rs = connection.getMetaData().getTables(null, "public", table,
                     new String[]{"TABLE"})) {
            return rs.next();
        }
    }
}
