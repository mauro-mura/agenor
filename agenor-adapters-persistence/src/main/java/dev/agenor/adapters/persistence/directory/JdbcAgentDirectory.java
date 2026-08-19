package dev.agenor.adapters.persistence.directory;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.agenor.adapters.persistence.JdbcHelper;
import dev.agenor.core.telemetry.AgenorTelemetry;

import java.io.Closeable;
import java.time.Duration;
import java.util.Objects;

/**
 * Factory and lifecycle holder for JDBC-backed agent directory capabilities.
 *
 * <p>Creates and owns the HikariCP connection pool, runs Flyway schema migrations on
 * construction, and exposes all four JDBC capability implementations. Presence arrived with
 * ADR-028 Phase A, which amended ADR-023's exclusion of it; see {@link JdbcAgentPresence}
 * for the staleness window it needs and for what has to drive its heartbeats.
 *
 * <p>Typical usage in a Spring Boot application:
 *
 * <pre>{@code
 * // Auto-configured when agenor.directory.provider=jdbc
 * // Manual wiring:
 * var dir = JdbcAgentDirectory.create(JdbcDirectoryConfig.of(url, user, pass));
 * AgenorRuntime runtime = AgenorRuntime.builder()
 *     .agentRegistry(dir.registry())
 *     .agentDiscovery(dir.discovery())
 *     .agentResolver(dir.resolver())
 *     .agentPresence(dir.presence())
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
    private final JdbcHelper helper;
    private final AgenorTelemetry telemetry;
    private final JdbcAgentRegistry registry;
    private final JdbcAgentDiscovery discovery;
    private final JdbcAgentResolver resolver;
    private final JdbcAgentPresence presence;

    private JdbcAgentDirectory(HikariDataSource dataSource,
                                JdbcHelper helper,
                                AgenorTelemetry telemetry,
                                JdbcAgentRegistry registry,
                                JdbcAgentDiscovery discovery,
                                JdbcAgentResolver resolver,
                                JdbcAgentPresence presence) {
        this.dataSource = dataSource;
        this.helper     = helper;
        this.telemetry  = telemetry;
        this.registry   = registry;
        this.discovery  = discovery;
        this.resolver   = resolver;
        this.presence   = presence;
    }

    /**
     * Creates a new {@code JdbcAgentDirectory} from the given configuration with noop telemetry.
     *
     * <p>Applies Flyway schema migrations before returning, so the
     * directory is immediately usable after this call returns.
     *
     * @param config JDBC directory configuration; must not be null
     * @return a fully initialised directory instance
     * @throws RuntimeException if schema migration or pool creation fails
     */
    public static JdbcAgentDirectory create(JdbcDirectoryConfig config) {
        return create(config, AgenorTelemetry.noop());
    }

    /**
     * Creates a new {@code JdbcAgentDirectory} from the given configuration and telemetry.
     *
     * <p>Applies Flyway schema migrations before returning, so the
     * directory is immediately usable after this call returns.
     *
     * @param config    JDBC directory configuration; must not be null
     * @param telemetry telemetry for {@code directory.*} spans; null treated as noop
     * @return a fully initialised directory instance
     * @throws RuntimeException if schema migration or pool creation fails
     */
    public static JdbcAgentDirectory create(JdbcDirectoryConfig config, AgenorTelemetry telemetry) {
        Objects.requireNonNull(config, "config must not be null");
        var t = telemetry != null ? telemetry : AgenorTelemetry.noop();

        var hikari = new HikariConfig();
        hikari.setJdbcUrl(config.jdbcUrl());
        hikari.setUsername(config.username());
        hikari.setPassword(config.password());
        hikari.setMaximumPoolSize(config.maximumPoolSize());
        hikari.setPoolName("agenor-directory");
        var ds = new HikariDataSource(hikari);

        new DirectorySchemaManager(ds, config.migrationLocation()).migrate();

        var helper = new JdbcHelper(ds);
        return new JdbcAgentDirectory(
                ds, helper, t,
                new JdbcAgentRegistry(helper, t),
                new JdbcAgentDiscovery(helper, t),
                new JdbcAgentResolver(helper, t),
                new JdbcAgentPresence(helper, t, JdbcAgentPresence.DEFAULT_STALENESS_WINDOW));
    }

    /**
     * Returns the JDBC-backed {@link dev.agenor.core.directory.AgentRegistry}.
     *
     * @return the registry; never null
     */
    public JdbcAgentRegistry registry() {
        return registry;
    }

    /**
     * Returns the JDBC-backed {@link dev.agenor.core.directory.AgentDiscovery}.
     *
     * @return the discovery; never null
     */
    public JdbcAgentDiscovery discovery() {
        return discovery;
    }

    /**
     * Returns the JDBC-backed {@link dev.agenor.core.directory.AgentResolver}.
     *
     * @return the resolver; never null
     */
    public JdbcAgentResolver resolver() {
        return resolver;
    }

    /**
     * Returns the JDBC-backed {@link dev.agenor.core.directory.AgentPresence}, using
     * {@link JdbcAgentPresence#DEFAULT_STALENESS_WINDOW}.
     *
     * <p>The default window expires a status after 90 seconds without a heartbeat, so it is
     * only meaningful if something is heartbeating. Where nothing does, ask for
     * {@link JdbcAgentPresence#UNBOUNDED_STALENESS_WINDOW} via {@link #presence(Duration)}.
     *
     * @return the presence backend; never null
     */
    public JdbcAgentPresence presence() {
        return presence;
    }

    /**
     * Returns a JDBC-backed {@link dev.agenor.core.directory.AgentPresence} with a chosen
     * staleness window.
     *
     * <p>Unlike the other accessors this builds a new instance per call, because the window
     * is a property of the view rather than of the connection pool. The instances are
     * stateless and share this directory's pool.
     *
     * @param stalenessWindow how long an agent may go unseen before its status reads
     *                        {@code UNKNOWN}; must not be null or negative
     * @return a presence backend using that window; never null
     * @throws NullPointerException     if stalenessWindow is null
     * @throws IllegalArgumentException if stalenessWindow is negative
     */
    public JdbcAgentPresence presence(Duration stalenessWindow) {
        return new JdbcAgentPresence(helper, telemetry, stalenessWindow);
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
