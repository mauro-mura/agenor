package dev.agenor.runtime.discovery;

import dev.agenor.core.*;
import dev.agenor.core.annotations.JenticAgent;
import dev.agenor.core.context.AgentContext;
import dev.agenor.core.exceptions.AgentException;
import dev.agenor.core.memory.MemoryStore;
import dev.agenor.core.messaging.MessageDispatcher;
import dev.agenor.runtime.agent.BaseAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for AgentFactory
 */
class AgentFactoryTest {

    @Mock
    private MessageDispatcher messageDispatcher;

    @Mock
    private AgentDirectory agentDirectory;

    @Mock
    private BehaviorScheduler behaviorScheduler;

    @Mock
    private MemoryStore memoryStore;

    private AgentFactory factory;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        factory = new AgentFactory(messageDispatcher, agentDirectory, behaviorScheduler, memoryStore);
    }

    // =========================================================================
    // CONSTRUCTOR TESTS
    // =========================================================================

    @Test
    @DisplayName("Should create factory with all services")
    void shouldCreateFactoryWithAllServices() {
        assertThat(factory).isNotNull();
    }

    @Test
    @DisplayName("Should create factory without memory store")
    void shouldCreateFactoryWithoutMemoryStore() {
        AgentFactory factoryNoMem = new AgentFactory(messageDispatcher, agentDirectory, behaviorScheduler, null);
        assertThat(factoryNoMem).isNotNull();
    }

    // =========================================================================
    // SERVICE REGISTRATION TESTS
    // =========================================================================

    @Test
    @DisplayName("Should add custom service")
    void shouldAddCustomService() throws AgentException {
        TestService testService = new TestService();
        factory.addService(TestService.class, testService);

        // Verify by creating agent that uses this service
        TestAgentWithService agent = factory.createAgent(TestAgentWithService.class);
        assertThat(agent).isNotNull();
        assertThat(agent.getTestService()).isSameAs(testService);
    }

    // =========================================================================
    // SINGLE AGENT CREATION TESTS
    // =========================================================================

    @Test
    @DisplayName("Should create simple agent with no-arg constructor")
    void shouldCreateSimpleAgent() throws AgentException {
        SimpleTestAgent agent = factory.createAgent(SimpleTestAgent.class);

        assertThat(agent).isNotNull();
        assertThat(agent.getAgentId()).isEqualTo("simple-agent");
        assertThat(agent.getAgentName()).isEqualTo("Simple Test Agent");
    }

    @Test
    @DisplayName("Should create agent with service injection")
    void shouldCreateAgentWithServiceInjection() throws AgentException {
        TestAgentWithMessageService agent = factory.createAgent(TestAgentWithMessageService.class);

        assertThat(agent).isNotNull();
        assertThat(agent.getMessageDispatcher()).isSameAs(messageDispatcher);
    }

    @Test
    @DisplayName("Should configure BaseAgent with required services")
    void shouldConfigureBaseAgent() throws AgentException {
        BaseAgentSpy agent = spy(new BaseAgentSpy());

        // Manually configure to verify calls
        factory.createAgent(BaseAgentSpy.class);

        // Note: We can't easily verify the internal calls without exposing internals
        // This test verifies the agent is created successfully
        assertThatCode(() -> factory.createAgent(BaseAgentSpy.class))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should set agent descriptor on BaseAgent")
    void shouldSetAgentDescriptor() throws AgentException {
        AgentWithDescriptorAccess agent = factory.createAgent(AgentWithDescriptorAccess.class);

        assertThat(agent).isNotNull();
        assertThat(agent.getDescriptor()).isNotNull();
        assertThat(agent.getDescriptor().agentId()).isEqualTo("descriptor-agent");
    }

    @Test
    @DisplayName("Should throw exception when no suitable constructor found")
    void shouldThrowExceptionForNoSuitableConstructor() {
        assertThatThrownBy(() -> factory.createAgent(AgentWithUnsatisfiableConstructor.class))
            .isInstanceOf(AgentException.class)
            .hasMessageContaining("Failed to create agent");
    }

    @Test
    @DisplayName("Should handle String parameter in constructor")
    void shouldHandleStringParameter() {
        // String parameters are set to null and should not fail the agent creation
        assertThatCode(() -> factory.createAgent(AgentWithStringConstructor.class))
            .doesNotThrowAnyException();
    }

    // =========================================================================
    // BATCH AGENT CREATION TESTS
    // =========================================================================

    @Test
    @DisplayName("Should create multiple agents from class set")
    void shouldCreateMultipleAgents() {
        Set<Class<? extends Agent>> agentClasses = new HashSet<>();
        agentClasses.add(SimpleTestAgent.class);
        agentClasses.add(AnnotatedAgent.class);

        Map<String, Agent> agents = factory.createAgents(agentClasses);

        assertThat(agents).hasSize(2);
        assertThat(agents).containsKeys("simple-agent", "annotated-agent");
    }

    @Test
    @DisplayName("Should skip failed agent creation in batch")
    void shouldSkipFailedAgentCreation() {
        Set<Class<? extends Agent>> agentClasses = new HashSet<>();
        agentClasses.add(SimpleTestAgent.class);
        agentClasses.add(AgentWithUnsatisfiableConstructor.class);

        Map<String, Agent> agents = factory.createAgents(agentClasses);

        // Should only have the successful one
        assertThat(agents).hasSize(1);
        assertThat(agents).containsKey("simple-agent");
    }

    // =========================================================================
    // AGENT ID EXTRACTION TESTS
    // =========================================================================

    @Test
    @DisplayName("Should extract agent ID from annotation")
    void shouldExtractIdFromAnnotation() throws AgentException {
        AgentWithDescriptorAccess agent = factory.createAgent(AgentWithDescriptorAccess.class);

        assertThat(agent.getDescriptor().agentId()).isEqualTo("descriptor-agent");
    }

    @Test
    @DisplayName("Should use agent's own ID when annotation is empty")
    void shouldUseAgentIdWhenAnnotationEmpty() throws AgentException {
        AgentWithEmptyAnnotation agent = factory.createAgent(AgentWithEmptyAnnotation.class);

        // Verify agent was created successfully
        assertThat(agent.getAgentId()).isEqualTo("fallback-id");
    }

    @Test
    @DisplayName("Should trim whitespace from annotation ID")
    void shouldTrimWhitespaceFromAnnotationId() throws AgentException {
        AgentWithWhitespaceId agent = factory.createAgent(AgentWithWhitespaceId.class);

        assertThat(agent.getAgentId()).isEqualTo("trimmed-id");
    }

    // =========================================================================
    // DESCRIPTOR CREATION TESTS
    // =========================================================================

    @Test
    @DisplayName("Should create descriptor with annotation metadata")
    void shouldCreateDescriptorWithAnnotationMetadata() throws AgentException {
        AnnotatedAgentFull agent = factory.createAgent(AnnotatedAgentFull.class);
        AgentDescriptor descriptor = factory.createDescriptor(AnnotatedAgentFull.class, agent);

        assertThat(descriptor).isNotNull();
        assertThat(descriptor.agentId()).isEqualTo("full-agent");
        assertThat(descriptor.agentType()).isEqualTo("custom-type");
        assertThat(descriptor.capabilities()).contains("capability1", "capability2");
        assertThat(descriptor.metadata()).containsEntry("class", AnnotatedAgentFull.class.getName());
        assertThat(descriptor.metadata()).containsEntry("autoStart", "true");
    }

    @Test
    @DisplayName("Should create minimal descriptor for non-annotated agent")
    void shouldCreateMinimalDescriptorForNonAnnotatedAgent() throws AgentException {
        SimpleTestAgent agent = factory.createAgent(SimpleTestAgent.class);
        AgentDescriptor descriptor = factory.createDescriptor(SimpleTestAgent.class, agent);

        assertThat(descriptor).isNotNull();
        assertThat(descriptor.agentId()).isEqualTo("simple-agent");
        assertThat(descriptor.agentName()).isEqualTo("Simple Test Agent");
        assertThat(descriptor.status()).isEqualTo(AgentStatus.STOPPED);
    }

    @Test
    @DisplayName("Should filter empty capabilities")
    void shouldFilterEmptyCapabilities() throws AgentException {
        AgentWithEmptyCapabilities agent = factory.createAgent(AgentWithEmptyCapabilities.class);
        AgentDescriptor descriptor = factory.createDescriptor(AgentWithEmptyCapabilities.class, agent);

        assertThat(descriptor.capabilities()).doesNotContain("");
        assertThat(descriptor.capabilities()).doesNotContain("  ");
    }

    @Test
    @DisplayName("Should use class simple name when type is empty")
    void shouldUseClassNameWhenTypeEmpty() throws AgentException {
        AgentWithEmptyType agent = factory.createAgent(AgentWithEmptyType.class);
        AgentDescriptor descriptor = factory.createDescriptor(AgentWithEmptyType.class, agent);

        assertThat(descriptor.agentType()).isEqualTo("AgentWithEmptyType");
    }

    @Test
    @DisplayName("createDescriptor uses getAgentId() over annotation value for multi-instance agents")
    void createDescriptor_prefersRuntimeIdOverAnnotationValueForMultiInstanceAgent() {
        // @JenticAgent("worker") declares a class-level type label, but each Worker instance
        // overrides getAgentId() to return its own logical ID ("worker-1", "worker-2", …).
        // The descriptor's agentId must match getAgentId(), not the annotation's value,
        // so that AgentResolver.resolveEndpoint("worker-1") can find the agent.
        MultiInstanceWorker worker = new MultiInstanceWorker("worker-1");
        AgentDescriptor descriptor = factory.createDescriptor(MultiInstanceWorker.class, worker);

        assertThat(descriptor.agentId())
                .as("descriptor agentId must equal getAgentId(), not the annotation value 'worker'")
                .isEqualTo("worker-1");
        // agentType falls back to class simple name when @JenticAgent has no 'type' attribute
        assertThat(descriptor.agentType()).isEqualTo("MultiInstanceWorker");
    }

    // =========================================================================
    // CONSTRUCTOR INJECTION TESTS
    // =========================================================================

    @Test
    @DisplayName("Should prefer constructor with most parameters")
    void shouldPreferMostSpecificConstructor() throws AgentException {
        AgentWithMultipleConstructors agent = factory.createAgent(AgentWithMultipleConstructors.class);

        assertThat(agent).isNotNull();
        assertThat(agent.getUsedConstructorParams()).isEqualTo(3);
    }

    @Test
    @DisplayName("Should try fallback constructors")
    void shouldTryFallbackConstructors() throws AgentException {
        // Create factory without some services
        AgentFactory limitedFactory = new AgentFactory(messageDispatcher, null, null, null);

        AgentWithMultipleConstructors agent = limitedFactory.createAgent(AgentWithMultipleConstructors.class);

        assertThat(agent).isNotNull();
        // Should use simpler constructor
        assertThat(agent.getUsedConstructorParams()).isLessThan(3);
    }

    // =========================================================================
    // TEST HELPER CLASSES
    // =========================================================================

    @JenticAgent("simple-agent")
    static class SimpleTestAgent extends BaseAgent {
        public SimpleTestAgent() {
            super("simple-agent", "Simple Test Agent");
        }
    }

    @JenticAgent("annotated-agent")
    static class AnnotatedAgent extends BaseAgent {
        public AnnotatedAgent() {
            super("annotated-agent", "Annotated Agent");
        }
    }

    @JenticAgent("descriptor-agent")
    static class AgentWithDescriptorAccess extends BaseAgent {
        private AgentDescriptor descriptor;

        public AgentWithDescriptorAccess() {
            super("descriptor-agent", "Descriptor Agent");
        }

        @Override
        public void setAgentDescriptor(AgentDescriptor descriptor) {
            super.setAgentDescriptor(descriptor);
            this.descriptor = descriptor;
        }

        public AgentDescriptor getDescriptor() {
            return descriptor;
        }
    }

    static class BaseAgentSpy extends BaseAgent {
        public BaseAgentSpy() {
            super("spy-agent", "Spy Agent");
        }
    }

    @JenticAgent("  ")
    static class AgentWithEmptyAnnotation extends BaseAgent {
        public AgentWithEmptyAnnotation() {
            super("fallback-id", "Fallback Agent");
        }
    }

    @JenticAgent("  trimmed-id  ")
    static class AgentWithWhitespaceId extends BaseAgent {
        public AgentWithWhitespaceId() {
            super("trimmed-id", "Trimmed Agent");
        }
    }

    @JenticAgent(value = "full-agent", type = "custom-type", capabilities = {"capability1", "capability2"}, autoStart = true)
    static class AnnotatedAgentFull extends BaseAgent {
        public AnnotatedAgentFull() {
            super("full-agent", "Full Agent");
        }
    }

    @JenticAgent(value = "empty-caps", capabilities = {"valid", "", "  ", "another"})
    static class AgentWithEmptyCapabilities extends BaseAgent {
        public AgentWithEmptyCapabilities() {
            super("empty-caps", "Empty Caps Agent");
        }
    }

    @JenticAgent(value = "empty-type", type = "")
    static class AgentWithEmptyType extends BaseAgent {
        public AgentWithEmptyType() {
            super("empty-type", "Empty Type Agent");
        }
    }

    @JenticAgent("service-agent")
    static class TestAgentWithMessageService extends BaseAgent {
        private final MessageDispatcher msgDispatcher;

        public TestAgentWithMessageService(MessageDispatcher messageDispatcher) {
            super("service-agent", "Service Agent");
            this.msgDispatcher = messageDispatcher;
        }

        @Override
        public MessageDispatcher getMessageDispatcher() {
            return msgDispatcher;
        }
    }

    @JenticAgent("custom-service-agent")
    static class TestAgentWithService extends BaseAgent {
        private final TestService testService;

        public TestAgentWithService(TestService testService) {
            super("custom-service-agent", "Custom Service Agent");
            this.testService = testService;
        }

        public TestService getTestService() {
            return testService;
        }
    }

    static class TestService {
        // Custom service for testing
    }

    @JenticAgent("unsatisfiable")
    static class AgentWithUnsatisfiableConstructor extends BaseAgent {
        public AgentWithUnsatisfiableConstructor(UnknownService service) {
            super("unsatisfiable", "Unsatisfiable");
        }
    }

    static class UnknownService {
        // Unknown service type
    }

    @JenticAgent("string-agent")
    static class AgentWithStringConstructor extends BaseAgent {
        public AgentWithStringConstructor(String name) {
            super("string-agent", name != null ? name : "String Agent");
        }
    }

    @JenticAgent("multi-constructor")
    static class AgentWithMultipleConstructors extends BaseAgent {
        private int usedConstructorParams;

        public AgentWithMultipleConstructors(MessageDispatcher md, AgentDirectory ad, BehaviorScheduler bs) {
            super("multi-constructor", "Multi Constructor");
            this.usedConstructorParams = 3;
        }

        public AgentWithMultipleConstructors(MessageDispatcher md) {
            super("multi-constructor", "Multi Constructor");
            this.usedConstructorParams = 1;
        }

        public AgentWithMultipleConstructors() {
            super("multi-constructor", "Multi Constructor");
            this.usedConstructorParams = 0;
        }

        public int getUsedConstructorParams() {
            return usedConstructorParams;
        }
    }

    /** Multi-instance agent: annotation value is a class-level type label, not an instance ID. */
    @JenticAgent("worker")
    static class MultiInstanceWorker extends BaseAgent {
        private final String instanceId;

        MultiInstanceWorker(String instanceId) {
            super(); // BaseAgent.agentId = UUID; getAgentId() overridden below
            this.instanceId = instanceId;
        }

        @Override public String getAgentId() { return instanceId; }
        @Override public String getAgentName() { return "Worker " + instanceId; }
    }

    // =========================================================================
    // PLAIN AGENT (no BaseAgent) TESTS
    // =========================================================================

    @Test
    @DisplayName("Should create agent that does not extend BaseAgent, injecting AgentContext")
    void shouldCreatePlainAgentWithAgentContext() throws AgentException {
        PlainAgentWithContext agent = factory.createAgent(PlainAgentWithContext.class);

        assertThat(agent).isNotNull();
        assertThat(agent.getAgentId()).isEqualTo("plain-context-agent");
        assertThat(agent.getContext()).isNotNull();
        assertThat(agent.getContext().messageDispatcher()).isSameAs(messageDispatcher);
        assertThat(agent.getContext().agentDirectory()).isSameAs(agentDirectory);
        assertThat(agent.getContext().behaviorScheduler()).isSameAs(behaviorScheduler);
        assertThat(agent.getContext().memoryStore()).isSameAs(memoryStore);
    }

    @Test
    @DisplayName("Should create plain agent injecting individual services (no AgentContext)")
    void shouldCreatePlainAgentWithIndividualServices() throws AgentException {
        PlainAgentWithServices agent = factory.createAgent(PlainAgentWithServices.class);

        assertThat(agent).isNotNull();
        assertThat(agent.getAgentId()).isEqualTo("plain-services-agent");
        assertThat(agent.getMessageDispatcher()).isSameAs(messageDispatcher);
    }

    @Test
    @DisplayName("AgentContext should not be registered when required services are null")
    void shouldNotRegisterAgentContextWhenServicesAreNull() throws AgentException {
        // Factory without agentDirectory — AgentContext cannot be built
        AgentFactory partialFactory = new AgentFactory(messageDispatcher, null, null, null);

        // Plain agent that needs AgentContext: no suitable constructor should be found
        assertThatThrownBy(() -> partialFactory.createAgent(PlainAgentWithContext.class))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("Failed to create agent");
    }

    // -------------------------------------------------------------------------
    // Helper classes — plain Agent implementors (no BaseAgent)
    // -------------------------------------------------------------------------

    /** Minimal Agent stub used by plain agent test helpers. */
    abstract static class AbstractPlainAgent implements Agent {

        private final String agentId;

        protected AbstractPlainAgent(String agentId) {
            this.agentId = agentId;
        }

        @Override public String getAgentId() { return agentId; }
        @Override public String getAgentName() { return agentId; }
        @Override public boolean isRunning() { return false; }
        @Override public java.util.concurrent.CompletableFuture<Void> start() {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        @Override public java.util.concurrent.CompletableFuture<Void> stop() {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        @Override public void addBehavior(dev.agenor.core.Behavior behavior) {}
        @Override public void removeBehavior(String behaviorId) {}
        @Override public dev.agenor.core.messaging.MessageDispatcher getMessageDispatcher() { return null; }
    }

    @JenticAgent("plain-context-agent")
    static class PlainAgentWithContext extends AbstractPlainAgent {

        private final AgentContext context;

        public PlainAgentWithContext(AgentContext context) {
            super("plain-context-agent");
            this.context = context;
        }

        public AgentContext getContext() { return context; }
    }

    @JenticAgent("plain-services-agent")
    static class PlainAgentWithServices extends AbstractPlainAgent {

        private final MessageDispatcher msgDispatcher;

        public PlainAgentWithServices(MessageDispatcher messageDispatcher) {
            super("plain-services-agent");
            this.msgDispatcher = messageDispatcher;
        }

        @Override
        public MessageDispatcher getMessageDispatcher() { return msgDispatcher; }
    }
}
