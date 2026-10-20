package dev.agenor.adapters.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link TimeZoneContractTest} on MySQL, where {@code TIMESTAMP} converts through the session's
 * zone rather than storing a bare wall-clock time.
 *
 * <p>Enable via: {@code mvn verify -Dintegration.tests.enabled=true -pl agenor-adapters-persistence}
 */
@Testcontainers
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
@EnabledIfDockerAvailable
@DisplayName("JDBC timestamps across time zones (MySQL)")
class TimeZoneMySqlIT extends TimeZoneContractTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    @Container
    static GenericContainer<?> mysql = MySqlContainers.mysql();

    @Override
    protected String freshDatabaseUrl() {
        var name = "tz_" + DATABASES.incrementAndGet();
        try (var c = DriverManager.getConnection(MySqlContainers.serverUrl(mysql), user(), password());
             var s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return MySqlContainers.serverUrl(mysql) + name;
    }

    @Override
    protected String user() {
        return "root";
    }

    @Override
    protected String password() {
        return MySqlContainers.ROOT_PASSWORD;
    }
}
