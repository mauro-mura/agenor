package dev.agenor.autoconfigure;

import dev.agenor.runtime.AgenorRuntime;

/**
 * One backend's share of the {@link AgenorRuntime} the starter builds.
 *
 * <p>Each optional backend (Redis messaging, the JDBC directory) used to declare its own
 * runtime factory. Only one of them could win {@code @ConditionalOnMissingBean}, so configuring
 * two backends silently dropped one: with Redis and JDBC together the runtime kept the
 * in-memory dispatcher, agents were advertised with no node, and cross-node messages were lost
 * without an error. There is now a single factory, and each backend contributes to its builder.
 *
 * <p>Package-private on purpose: it is how this class composes its own beans, not an extension
 * point. An application that needs a different runtime declares its own {@link AgenorRuntime}
 * bean.
 */
@FunctionalInterface
interface RuntimeBuilderContributor {

    /**
     * Applies this backend's components to the runtime being assembled.
     *
     * @param builder the runtime builder, already carrying configuration, LLM, telemetry and
     *                approval gate
     */
    void contribute(AgenorRuntime.Builder builder);
}
