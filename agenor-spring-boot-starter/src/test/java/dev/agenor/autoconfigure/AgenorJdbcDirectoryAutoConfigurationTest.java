package dev.agenor.autoconfigure;

import dev.agenor.adapters.persistence.directory.JdbcAgentDirectory;
import dev.agenor.core.AgentDescriptor;
import dev.agenor.core.AgentStatus;
import dev.agenor.core.directory.AgentDiscovery;
import dev.agenor.core.directory.AgentRegistry;
import dev.agenor.core.directory.AgentResolver;
import dev.agenor.runtime.AgenorRuntime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auto-configuration of the JDBC agent directory ({@code agenor.directory.provider=jdbc}).
 */
@DisplayName("Auto-configuration — JDBC directory")
class AgenorJdbcDirectoryAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgenorAutoConfiguration.class));

    private static String jdbcUrl(String name) {
        return "agenor.directory.jdbc.url=jdbc:h2:mem:jdbc_dir_ac_" + name
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
    }

    @Test
    @DisplayName("the context starts with a JDBC directory")
    void contextStarts() {
        // The capability beans and the runtime-derived AgentDirectory bean both answer to
        // AgentRegistry, AgentDiscovery and AgentResolver. Without a primary among them the
        // runtime factory has two candidates per parameter and the context never refreshes.
        runner.withPropertyValues("agenor.directory.provider=jdbc", jdbcUrl("starts"))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(AgenorRuntime.class);
                    assertThat(ctx).hasSingleBean(JdbcAgentDirectory.class);
                });
    }

    @Test
    @DisplayName("each capability resolves to the JDBC implementation")
    void capabilitiesResolveToJdbc() {
        // Given ambiguity between the JDBC beans and the runtime's own directory, injection
        // must land on the backend the user asked for
        runner.withPropertyValues("agenor.directory.provider=jdbc", jdbcUrl("caps"))
                .run(ctx -> {
                    var dir = ctx.getBean(JdbcAgentDirectory.class);
                    assertThat(ctx.getBean(AgentRegistry.class)).isSameAs(dir.registry());
                    assertThat(ctx.getBean(AgentDiscovery.class)).isSameAs(dir.discovery());
                    assertThat(ctx.getBean(AgentResolver.class)).isSameAs(dir.resolver());
                });
    }

    @Test
    @DisplayName("the runtime writes through to the database")
    void runtimeWritesToTheDatabase() {
        runner.withPropertyValues("agenor.directory.provider=jdbc", jdbcUrl("writes"))
                .run(ctx -> {
                    // Given a runtime built on the JDBC capability beans
                    var runtime = ctx.getBean(AgenorRuntime.class);
                    var dir = ctx.getBean(JdbcAgentDirectory.class);

                    // When an agent is registered through the runtime's directory
                    runtime.getAgentDirectory().register(AgentDescriptor.builder("stored-agent")
                            .agentName("Stored")
                            .agentType("DirectoryTestAgent")
                            .status(AgentStatus.RUNNING)
                            .build()).join();

                    // Then the row is in the database, not only in memory
                    assertThat(dir.discovery().findById("stored-agent").join()).isPresent();
                });
    }

    @Test
    @DisplayName("a missing URL fails with a message that says which property is missing")
    void missingUrlFailsClearly() {
        runner.withPropertyValues("agenor.directory.provider=jdbc")
                .run(ctx -> assertThat(ctx).getFailure()
                        .hasRootCauseMessage("agenor.directory.provider=jdbc requires "
                                + "agenor.directory.jdbc.url to be set"));
    }

    @Test
    @DisplayName("nothing JDBC is created without the property")
    void noJdbcBeansByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(JdbcAgentDirectory.class));
    }
}
