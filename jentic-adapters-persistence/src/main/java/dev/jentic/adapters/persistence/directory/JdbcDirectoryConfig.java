package dev.jentic.adapters.persistence.directory;

import java.util.Objects;

/**
 * Configuration for the JDBC agent directory backend.
 *
 * @param jdbcUrl           JDBC connection URL (e.g. {@code jdbc:postgresql://localhost:5432/mydb})
 * @param username          database username
 * @param password          database password
 * @param maximumPoolSize   HikariCP maximum pool size (default: 10)
 * @param migrationLocation Flyway classpath location for directory migrations
 * @since 0.22.0
 */
public record JdbcDirectoryConfig(
        String jdbcUrl,
        String username,
        String password,
        int maximumPoolSize,
        String migrationLocation
) {

    public JdbcDirectoryConfig {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
        if (maximumPoolSize < 1) throw new IllegalArgumentException("maximumPoolSize must be >= 1");
    }

    /**
     * Creates a config with defaults: pool size 10, standard migration location.
     */
    public static JdbcDirectoryConfig of(String jdbcUrl, String username, String password) {
        return new JdbcDirectoryConfig(
                jdbcUrl, username, password, 10,
                "classpath:db/migration/jentic-directory");
    }
}
