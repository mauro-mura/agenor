package dev.jentic.adapters.persistence.directory;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.jentic.adapters.persistence.JdbcHelper;

import java.io.Closeable;
import java.util.Objects;

/**
 * Factory and lifecycle holder for JDBC-backed agent directory capabilities.
 *
 * <p>Creates and owns the HikariCP connection pool, runs Flyway schema migrations on
 * construction, and exposes the three JDBC capability implementations. Presence
 * ({@link dev.jentic.core.directory.AgentPresence}) is intentionally omitted — see
 * its Javadoc for rationale (heartbeat-over-JDBC amplifies write load unnecessarily).
 *
 * <p>Typical usage in a Spring Boot application:
 *
 * <pre>{@code
 * // Auto-configured when jentic.directory.provider=jdbc
 * // Manual wiring:
 * var dir = JdbcAgentDirectory.create(JdbcDirectoryConfig.of(url, user, pass));
 * JenticRuntime runtime = JenticRuntime.builder()
 *     .agentRegistry(dir.registry())
 *     .agentDiscovery(dir.discovery())
 *     .agentResolver(dir.resolver())
 *     .build();
 * }</pre>
 *
 * <p>Declare as a {@code @Bean(destroyMethod = "close")} to release the connection pool
 * when the Spring context closes.
 *
 * @since 0.22.0
 */
public final class JdbcAgentDirectory implements Closeable {

    private final HikariDataSource dataSource;
    private final JdbcAgentRegistry registry;
    private final JdbcAgentDiscovery discovery;
    private final JdbcAgentResolver resolver;

    private JdbcAgentDirectory(HikariDataSource dataSource,
                                JdbcAgentRegistry registry,
                                JdbcAgentDiscovery discovery,
                                JdbcAgentResolver resolver) {
        this.dataSource = dataSource;
        this.registry   = registry;
        this.discovery  = discovery;
        this.resolver   = resolver;
    }

    /**
     * Creates a new {@code JdbcAgentDirectory} from the given configuration.
     *
     * <p>Applies Flyway schema migrations before returning, so the
     * directory is immediately usable after this call returns.
     *
     * @param config JDBC directory configuration; must not be null
     * @return a fully initialised directory instance
     * @throws RuntimeException if schema migration or pool creation fails
     */
    public static JdbcAgentDirectory create(JdbcDirectoryConfig config) {
        Objects.requireNonNull(config, "config must not be null");

        var hikari = new HikariConfig();
        hikari.setJdbcUrl(config.jdbcUrl());
        hikari.setUsername(config.username());
        hikari.setPassword(config.password());
        hikari.setMaximumPoolSize(config.maximumPoolSize());
        hikari.setPoolName("jentic-directory");
        var ds = new HikariDataSource(hikari);

        new DirectorySchemaManager(ds, config.migrationLocation()).migrate();

        var helper = new JdbcHelper(ds);
        return new JdbcAgentDirectory(
                ds,
                new JdbcAgentRegistry(helper),
                new JdbcAgentDiscovery(helper),
                new JdbcAgentResolver(helper));
    }

    /**
     * Returns the JDBC-backed {@link dev.jentic.core.directory.AgentRegistry}.
     *
     * @return the registry; never null
     */
    public JdbcAgentRegistry registry() {
        return registry;
    }

    /**
     * Returns the JDBC-backed {@link dev.jentic.core.directory.AgentDiscovery}.
     *
     * @return the discovery; never null
     */
    public JdbcAgentDiscovery discovery() {
        return discovery;
    }

    /**
     * Returns the JDBC-backed {@link dev.jentic.core.directory.AgentResolver}.
     *
     * @return the resolver; never null
     */
    public JdbcAgentResolver resolver() {
        return resolver;
    }

    /**
     * Closes the underlying HikariCP connection pool.
     *
     * <p>After this call the directory is no longer usable. In Spring Boot applications
     * this is invoked automatically via {@code destroyMethod = "close"}.
     */
    @Override
    public void close() {
        dataSource.close();
    }
}
