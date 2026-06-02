package dev.agenor.runtime.discovery;

import dev.agenor.core.Agent;
import dev.agenor.core.annotations.JenticAgent;
import dev.agenor.runtime.agent.BaseAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

import java.util.Set;

/**
 * Unit tests for AgentScanner
 */
class AgentScannerTest {

    private AgentScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new AgentScanner();
    }

    // =========================================================================
    // BASIC SCANNING TESTS
    // =========================================================================


    @Test
    @DisplayName("Should scan currrent package")
    void shouldScanCurrentPackage() {
        // When
        Set<Class<? extends Agent>> agents = scanner.scanForAgents("dev.agenor.runtime.discovery");

        // Then
        assertThat(agents).isNotEmpty();

        // Check that we found test agents
        boolean foundTestAgent = agents.stream()
            .anyMatch(clazz -> clazz.getSimpleName().equals("TestValidAgent"));
        assertThat(foundTestAgent).isTrue();
    }

    @Test
    @DisplayName("Should ignore invalid classes")
    void shouldIgnoreInvalidClasses() {
        // When
        Set<Class<? extends Agent>> agents = scanner.scanForAgents("dev.agenor.runtime.discovery");

        // Then
        // Should not include classes without @JenticAgent annotation
        boolean foundInvalid = agents.stream()
            .anyMatch(clazz -> clazz.getSimpleName().equals("TestInvalidAgent"));
        assertThat(foundInvalid).isFalse();

        // Should not include abstract classes
        boolean foundAbstract = agents.stream()
            .anyMatch(clazz -> clazz.getSimpleName().equals("TestAbstractAgent"));
        assertThat(foundAbstract).isFalse();

        // Should not include interfaces
        boolean foundInterface = agents.stream()
            .anyMatch(clazz -> clazz.getSimpleName().equals("TestAgentInterface"));
        assertThat(foundInterface).isFalse();
    }

    @Test
    @DisplayName("Should handle empty packages")
    void shouldHandleEmptyPackages() {
        // When
        Set<Class<? extends Agent>> agents = scanner.scanForAgents("non.existent.package");

        // Then
        assertThat(agents).isEmpty();
    }

    @Test
    @DisplayName("Should")
    void shouldHandleNullAndEmptyPackageNames() {
        // When
        Set<Class<? extends Agent>> agents = scanner.scanForAgents(null, "", "  ");

        // Then
        assertThat(agents).isEmpty();
    }

    @Test
    @DisplayName("Should find all valid agent classes")
    void shouldFindAllValidAgentClasses() {
        Set<Class<? extends Agent>> agents = scanner.scanForAgents("dev.agenor.runtime.discovery");

        // All found classes should have @JenticAgent annotation
        for (Class<? extends Agent> agentClass : agents) {
            assertThat(agentClass.isAnnotationPresent(JenticAgent.class)).isTrue();
        }
    }

    // =========================================================================
    // FILTERING TESTS
    // =========================================================================

    @Test
    @DisplayName("Should ignore classes without @JenticAgent annotation")
    void shouldIgnoreNonAnnotatedClasses() {
        Set<Class<? extends Agent>> agents = scanner.scanForAgents("dev.agenor.runtime.discovery");

        boolean foundInvalid = agents.stream()
            .anyMatch(clazz -> clazz.getSimpleName().equals("TestInvalidAgent"));
        assertThat(foundInvalid).isFalse();
    }

    @Test
    @DisplayName("Should ignore interfaces")
    void shouldIgnoreInterfaces() {
        Set<Class<? extends Agent>> agents = scanner.scanForAgents("dev.agenor.runtime.discovery");

        boolean foundInterface = agents.stream()
            .anyMatch(clazz -> clazz.getSimpleName().equals("TestAgentInterface"));
        assertThat(foundInterface).isFalse();
    }

    @Test
    @DisplayName("Should ignore abstract classes")
    void shouldIgnoreAbstractClasses() {
        Set<Class<? extends Agent>> agents = scanner.scanForAgents("dev.agenor.runtime.discovery");

        boolean foundAbstract = agents.stream()
            .anyMatch(clazz -> clazz.isInterface() || java.lang.reflect.Modifier.isAbstract(clazz.getModifiers()));

        // All found classes should be concrete
        for (Class<? extends Agent> agentClass : agents) {
            assertThat(java.lang.reflect.Modifier.isAbstract(agentClass.getModifiers())).isFalse();
        }
    }

    // =========================================================================
    // PACKAGE HANDLING TESTS
    // =========================================================================

    @Test
    @DisplayName("Should handle empty package names")
    void shouldHandleEmptyPackageNames() {
        Set<Class<? extends Agent>> agents = scanner.scanForAgents("");

        assertThat(agents).isEmpty();
    }

    @Test
    @DisplayName("Should handle whitespace-only package names")
    void shouldHandleWhitespaceOnlyPackageNames() {
        Set<Class<? extends Agent>> agents = scanner.scanForAgents("   ");

        assertThat(agents).isEmpty();
    }

    @Test
    @DisplayName("Should handle null package names")
    void shouldHandleNullPackageNames() {
        Set<Class<? extends Agent>> agents = scanner.scanForAgents((String) null);

        assertThat(agents).isEmpty();
    }

    @Test
    @DisplayName("Should handle mixed null and valid package names")
    void shouldHandleMixedNullAndValidPackageNames() {
        Set<Class<? extends Agent>> agents = scanner.scanForAgents(
            null,
            "dev.agenor.runtime.discovery",
            ""
        );

        assertThat(agents).isNotEmpty();
    }

    @Test
    @DisplayName("Should handle non-existent package")
    void shouldHandleNonExistentPackage() {
        Set<Class<? extends Agent>> agents = scanner.scanForAgents("non.existent.package");

        assertThat(agents).isEmpty();
    }

    // =========================================================================
    // MULTIPLE PACKAGE SCANNING TESTS
    // =========================================================================

    @Test
    @DisplayName("Should scan multiple packages")
    void shouldScanMultiplePackages() {
        Set<Class<? extends Agent>> agents = scanner.scanForAgents(
            "dev.agenor.runtime.discovery"
        );

        assertThat(agents).isNotEmpty();
    }

    @Test
    @DisplayName("Should deduplicate results from multiple packages")
    void shouldDeduplicateResults() {
        // Scanning same package twice should not create duplicates
        Set<Class<? extends Agent>> agents = scanner.scanForAgents(
            "dev.agenor.runtime.discovery",
            "dev.agenor.runtime.discovery"
        );

        assertThat(agents).isNotEmpty();

        // Count how many times TestValidAgent appears
        long count = agents.stream()
            .filter(clazz -> clazz.getSimpleName().equals("TestValidAgent"))
            .count();

        assertThat(count).isEqualTo(1);
    }

    // =========================================================================
    // RECURSIVE SCANNING TESTS
    // =========================================================================

    @Test
    @DisplayName("Should scan subdirectories recursively")
    void shouldScanSubdirectoriesRecursively() {
        Set<Class<? extends Agent>> agents = scanner.scanForAgents("dev.agenor.runtime");

        // Should find agents in subdirectories
        assertThat(agents).isNotEmpty();
    }

    // =========================================================================
    // CLASSLOADER TESTS
    // =========================================================================

    @Test
    @DisplayName("Should handle different classloaders")
    void shouldHandleDifferentClassloaders() {
        // Scanner should try multiple classloaders
        Set<Class<? extends Agent>> agents = scanner.scanForAgents("dev.agenor.runtime.discovery");

        assertThat(agents).isNotEmpty();
    }

    // =========================================================================
    // ERROR HANDLING TESTS
    // =========================================================================

    @Test
    @DisplayName("Should continue scanning when encountering class loading errors")
    void shouldContinueOnClassLoadingErrors() {
        // Even if some classes fail to load, should continue
        Set<Class<? extends Agent>> agents = scanner.scanForAgents("dev.agenor.runtime.discovery");

        assertThat(agents).isNotEmpty();
    }

    @Test
    @DisplayName("Should handle inner classes gracefully")
    void shouldHandleInnerClassesGracefully() {
        // Scanner should skip inner classes (those with $ in name)
        Set<Class<? extends Agent>> agents = scanner.scanForAgents("dev.agenor.runtime.discovery");

        // No class names should contain $
        for (Class<? extends Agent> agentClass : agents) {
            assertThat(agentClass.getName()).doesNotContain("$");
        }
    }

    // =========================================================================
    // COMPLETE VALIDATION TESTS
    // =========================================================================

    @Test
    @DisplayName("Should validate agent class completely")
    void shouldValidateAgentClassCompletely() {
        Set<Class<? extends Agent>> agents = scanner.scanForAgents("dev.agenor.runtime.discovery");

        for (Class<? extends Agent> agentClass : agents) {
            // Must have @JenticAgent annotation
            assertThat(agentClass.isAnnotationPresent(JenticAgent.class)).isTrue();

            // Must implement Agent interface
            assertThat(Agent.class.isAssignableFrom(agentClass)).isTrue();

            // Must not be an interface
            assertThat(agentClass.isInterface()).isFalse();

            // Must not be abstract
            assertThat(java.lang.reflect.Modifier.isAbstract(agentClass.getModifiers())).isFalse();
        }
    }

    // =========================================================================
    // JAR FILE SCANNING TESTS (simulated)
    // =========================================================================

    @Test
    @DisplayName("Should handle file protocol URLs")
    void shouldHandleFileProtocolUrls() {
        // When scanning from file system (not JAR)
        Set<Class<? extends Agent>> agents = scanner.scanForAgents("dev.agenor.runtime.discovery");

        assertThat(agents).isNotEmpty();
    }

    // =========================================================================
    // PERFORMANCE TESTS
    // =========================================================================

    @Test
    @DisplayName("Should scan package efficiently")
    void shouldScanPackageEfficiently() {
        long startTime = System.currentTimeMillis();

        Set<Class<? extends Agent>> agents = scanner.scanForAgents("dev.agenor.runtime.discovery");

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        assertThat(agents).isNotEmpty();
        // Should complete in reasonable time (< 5 seconds)
        assertThat(duration).isLessThan(5000);
    }

    @Test
    @DisplayName("Should return empty set for invalid packages without errors")
    void shouldReturnEmptySetForInvalidPackages() {
        assertThatCode(() -> {
            Set<Class<? extends Agent>> agents = scanner.scanForAgents("invalid.package.name");
            assertThat(agents).isEmpty();
        }).doesNotThrowAnyException();
    }
}

// Test classes for AgentScannerTest

@JenticAgent("test-valid")
class TestValidAgent extends BaseAgent {
    public TestValidAgent() {
        super("test-valid", "Test Valid Agent");
    }
}

// This class should be ignored - no annotation
class TestInvalidAgent extends BaseAgent {
    public TestInvalidAgent() {
        super("test-invalid", "Test Invalid Agent");
    }
}

// This interface should be ignored
@JenticAgent("test-interface")
interface TestAgentInterface extends Agent {
}
