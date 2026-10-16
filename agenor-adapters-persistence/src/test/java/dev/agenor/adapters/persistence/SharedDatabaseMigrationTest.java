package dev.agenor.adapters.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;

import javax.sql.DataSource;

/**
 * {@link SharedDatabaseMigrationContractTest} on H2, so the normal build runs it.
 * {@code SharedDatabaseMigrationIT} runs the same contract on PostgreSQL.
 */
@DisplayName("Shared database migrations — H2")
class SharedDatabaseMigrationTest extends SharedDatabaseMigrationContractTest {

    @Override
    protected DataSource freshDatabase() {
        var cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:h2:mem:shared_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        cfg.setUsername("sa");
        cfg.setPassword("");
        cfg.setMaximumPoolSize(2);
        return new HikariDataSource(cfg);
    }

    @Override
    protected void closeDatabase(DataSource dataSource) {
        ((HikariDataSource) dataSource).close();
    }
}
