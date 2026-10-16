package dev.agenor.adapters.persistence;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;

/** The MySQL server the MySQL integration tests share their setup for. */
final class MySqlContainers {

    static final String ROOT_PASSWORD = "agenor_test";

    private MySqlContainers() {}

    static GenericContainer<?> mysql() {
        return new GenericContainer<>("mysql:8.4")
                .withExposedPorts(3306)
                .withEnv("MYSQL_ROOT_PASSWORD", ROOT_PASSWORD)
                .withEnv("MYSQL_DATABASE", "agenor_test")
                // Like Postgres, the entrypoint runs a temporary server for initialisation
                // first; that one listens on port 0, the real one on 3306.
                .waitingFor(Wait.forLogMessage(".*ready for connections.*port: 3306.*", 1))
                .withStartupTimeout(Duration.ofMinutes(3));
    }

    /** The server's URL without a database, ending in {@code /}. */
    static String serverUrl(GenericContainer<?> mysql) {
        return "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/";
    }
}
