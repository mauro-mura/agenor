package dev.agenor.adapters.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link SharedDatabaseMigrationContractTest} on MySQL, one new database per test.
 *
 * <p>Enable via: {@code mvn verify -Dintegration.tests.enabled=true -pl agenor-adapters-persistence}
 */
@Testcontainers
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
@EnabledIfDockerAvailable
@DisplayName("Shared database migrations — MySQL")
class SharedDatabaseMigrationMySqlIT extends SharedDatabaseMigrationContractTest {

    @Container
    static GenericContainer<?> mysql = MySqlContainers.mysql();

    private static final AtomicInteger DATABASES = new AtomicInteger();

    @Override
    protected DataSource freshDatabase() {
        var name = "shared_" + DATABASES.incrementAndGet();
        try (var c = DriverManager.getConnection(MySqlContainers.serverUrl(mysql), "root", MySqlContainers.ROOT_PASSWORD);
             var s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        var cfg = new HikariConfig();
        cfg.setJdbcUrl(MySqlContainers.serverUrl(mysql) + name);
        cfg.setUsername("root");
        cfg.setPassword(MySqlContainers.ROOT_PASSWORD);
        cfg.setMaximumPoolSize(2);
        return new HikariDataSource(cfg);
    }

    @Override
    protected void closeDatabase(DataSource dataSource) {
        ((HikariDataSource) dataSource).close();
    }
}
