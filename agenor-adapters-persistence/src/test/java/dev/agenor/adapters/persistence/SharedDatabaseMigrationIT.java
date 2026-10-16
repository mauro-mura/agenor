package dev.agenor.adapters.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link SharedDatabaseMigrationContractTest} on PostgreSQL, one new database per test.
 *
 * <p>Enable via: {@code mvn verify -Dintegration.tests.enabled=true -pl agenor-adapters-persistence}
 */
@Testcontainers
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
@EnabledIfDockerAvailable
@DisplayName("Shared database migrations — PostgreSQL")
class SharedDatabaseMigrationIT extends SharedDatabaseMigrationContractTest {

    @Container
    static GenericContainer<?> postgres = new GenericContainer<>("postgres:16-alpine")
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", "agenor_test")
            .withEnv("POSTGRES_USER", "agenor")
            .withEnv("POSTGRES_PASSWORD", "agenor_test")
            // Postgres opens its port before it will accept connections: wait for the second
            // time the real server announces itself, as the other ITs in this module do.
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2))
            .withStartupTimeout(Duration.ofSeconds(60));

    private static final AtomicInteger DATABASES = new AtomicInteger();

    @Override
    protected DataSource freshDatabase() {
        var base = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/";
        var name = "shared_" + DATABASES.incrementAndGet();
        try (var c = DriverManager.getConnection(base + "agenor_test", "agenor", "agenor_test");
             var s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        var cfg = new HikariConfig();
        cfg.setJdbcUrl(base + name);
        cfg.setUsername("agenor");
        cfg.setPassword("agenor_test");
        cfg.setMaximumPoolSize(2);
        return new HikariDataSource(cfg);
    }

    @Override
    protected void closeDatabase(DataSource dataSource) {
        ((HikariDataSource) dataSource).close();
    }
}
