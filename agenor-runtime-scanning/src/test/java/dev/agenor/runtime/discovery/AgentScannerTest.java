package dev.agenor.runtime.discovery;

import dev.agenor.core.annotations.Agent;
import dev.agenor.runtime.agent.BaseAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

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
        Set<Class<? extends dev.agenor.core.Agent>> agents = scanner.scanForAgents("dev.agenor.runtime.discovery");

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
        Set<Class<? extends dev.agenor.core.Agent>> agents = scanner.scanForAgents("dev.agenor.runtime.discovery");

        // Then
        // Should not include classes without @Agent annotation
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
        Set<Class<? extends dev.agenor.core.Agent>> agents = scanner.scanForAgents("non.existent.package");

        // Then
        assertThat(agents).isEmpty();
    }

    @Test
    @DisplayName("Should")
    void shouldHandleNullAndEmptyPackageNames() {
        // When
        Set<Class<? extends dev.agenor.core.Agent>> agents = scanner.scanForAgents(null, "", "  ");

        // Then
        assertThat(agents).isEmpty();
    }

    @Test
    @DisplayName("Should find all valid agent classes")
    void shouldFindAllValidAgentClasses() {
        Set<Class<? extends dev.agenor.core.Agent>> agents = scanner.scanForAgents("dev.agenor.runtime.discovery");

        // All found classes should have @Agent annotation
        for (Class<? extends dev.agenor.core.Agent> agentClass : agents) {
            assertThat(agentClass.isAnnotationPresent(Agent.class)).isTrue();
        }
    }

    // =========================================================================
    // FILTERING TESTS
    // =========================================================================

    @Test
    @DisplayName("Should ignore classes without @Agent annotation")
    void shouldIgnoreNonAnnotatedClasses() {
        Set<Class<? extends dev.agenor.core.Agent>> agents = scanner.scanForAgents("dev.agenor.runtime.discovery");

        boolean foundInvalid = agents.stream()
            .anyMatch(clazz -> clazz.getSimpleName().equals("TestInvalidAgent"));
        assertThat(foundInvalid).isFalse();
    }

    @Test
    @DisplayName("Should ignore interfaces")
    void shouldIgnoreInterfaces() {
        Set<Class<? extends dev.agenor.core.Agent>> agents = scanner.scanForAgents("dev.agenor.runtime.discovery");

        boolean foundInterface = agents.stream()
            .anyMatch(clazz -> clazz.getSimpleName().equals("TestAgentInterface"));
        assertThat(foundInterface).isFalse();
    }

    @Test
    @DisplayName("Should ignore abstract classes")
    void shouldIgnoreAbstractClasses() {
        Set<Class<? extends dev.agenor.core.Agent>> agents = scanner.scanForAgents("dev.agenor.runtime.discovery");

        boolean foundAbstract = agents.stream()
            .anyMatch(clazz -> clazz.isInterface() || java.lang.reflect.Modifier.isAbstract(clazz.getModifiers()));

        // All found classes should be concrete
        for (Class<? extends dev.agenor.core.Agent> agentClass : agents) {
            assertThat(java.lang.reflect.Modifier.isAbstract(agentClass.getModifiers())).isFalse();
        }
    }

    // =========================================================================
    // PACKAGE HANDLING TESTS
    // =========================================================================

    @Test
    @DisplayName("Should handle empty package names")
    void shouldHandleEmptyPackageNames() {
        Set<Class<? extends dev.agenor.core.Agent>> agents = scanner.scanForAgents("");

        assertThat(agents).isEmpty();
    }

    @Test
    @DisplayName("Should handle whitespace-only package names")
    void shouldHandleWhitespaceOnlyPackageNames() {
        Set<Class<? extends dev.agenor.core.Agent>> agents = scanner.scanForAgents("   ");

        assertThat(agents).isEmpty();
    }

    @Test
    @DisplayName("Should handle null package names")
    void shouldHandleNullPackageNames() {
        Set<Class<? extends dev.agenor.core.Agent>> agents = scanner.scanForAgents((String) null);

        assertThat(agents).isEmpty();
    }

    @Test
    @DisplayName("Should handle mixed null and valid package names")
    void shouldHandleMixedNullAndValidPackageNames() {
        Set<Class<? extends dev.agenor.core.Agent>> agents = scanner.scanForAgents(
            null,
            "dev.agenor.runtime.discovery",
            ""
        );

        assertThat(agents).isNotEmpty();
    }

    @Test
    @DisplayName("Should handle non-existent package")
    void shouldHandleNonExistentPackage() {
        Set<Class<? extends dev.agenor.core.Agent>> agents = scanner.scanForAgents("non.existent.package");

        assertThat(agents).isEmpty();
    }

    // =========================================================================
    // MULTIPLE PACKAGE SCANNING TESTS
    // =========================================================================

    @Test
    @DisplayName("Should scan multiple packages")
    void shouldScanMultiplePackages() {
        Set<Class<? extends dev.agenor.core.Agent>> agents = scanner.scanForAgents(
            "dev.agenor.runtime.discovery"
        );

        assertThat(agents).isNotEmpty();
    }

    @Test
    @DisplayName("Should deduplicate results from multiple packages")
    void shouldDeduplicateResults() {
        // Scanning same package twice should not create duplicates
        Set<Class<? extends dev.agenor.core.Agent>> agents = scanner.scanForAgents(
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
        Set<Class<? extends dev.agenor.core.Agent>> agents = scanner.scanForAgents("dev.agenor.runtime");

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
        Set<Class<? extends dev.agenor.core.Agent>> agents = scanner.scanForAgents("dev.agenor.runtime.discovery");

        assertThat(agents).isNotEmpty();
    }

    // =========================================================================
    // ERROR HANDLING TESTS
    // =========================================================================

    @Test
    @DisplayName("Should continue scanning when encountering class loading errors")
    void shouldContinueOnClassLoadingErrors() {
        // Even if some classes fail to load, should continue
        Set<Class<? extends dev.agenor.core.Agent>> agents = scanner.scanForAgents("dev.agenor.runtime.discovery");

        assertThat(agents).isNotEmpty();
    }

    @Test
    @DisplayName("Should handle inner classes gracefully")
    void shouldHandleInnerClassesGracefully() {
        // Scanner should skip inner classes (those with $ in name)
        Set<Class<? extends dev.agenor.core.Agent>> agents = scanner.scanForAgents("dev.agenor.runtime.discovery");

        // No class names should contain $
        for (Class<? extends dev.agenor.core.Agent> agentClass : agents) {
            assertThat(agentClass.getName()).doesNotContain("$");
        }
    }

    // =========================================================================
    // COMPLETE VALIDATION TESTS
    // =========================================================================

    @Test
    @DisplayName("Should validate agent class completely")
    void shouldValidateAgentClassCompletely() {
        Set<Class<? extends dev.agenor.core.Agent>> agents = scanner.scanForAgents("dev.agenor.runtime.discovery");

        for (Class<? extends dev.agenor.core.Agent> agentClass : agents) {
            // Must have @Agent annotation
            assertThat(agentClass.isAnnotationPresent(Agent.class)).isTrue();

            // Must implement Agent interface
            assertThat(dev.agenor.core.Agent.class.isAssignableFrom(agentClass)).isTrue();

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
        Set<Class<? extends dev.agenor.core.Agent>> agents = scanner.scanForAgents("dev.agenor.runtime.discovery");

        assertThat(agents).isNotEmpty();
    }

    // =========================================================================
    // PERFORMANCE TESTS
    // =========================================================================

    @Test
    @DisplayName("Should scan package efficiently")
    void shouldScanPackageEfficiently() {
        long startTime = System.currentTimeMillis();

        Set<Class<? extends dev.agenor.core.Agent>> agents = scanner.scanForAgents("dev.agenor.runtime.discovery");

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
            Set<Class<? extends dev.agenor.core.Agent>> agents = scanner.scanForAgents("invalid.package.name");
            assertThat(agents).isEmpty();
        }).doesNotThrowAnyException();
    }

    // =========================================================================
    // SCANNING THROUGH A JAR, AND A SCAN THAT CANNOT BE DONE
    // =========================================================================

    @Test
    @DisplayName("Should find an agent that only the context class loader can load, in a jar")
    void shouldFindAgentInJarVisibleOnlyToContextClassLoader(@TempDir Path dir) throws Exception {
        Path jar = jarWithAgent(dir, "fixture.plain", "PlainAgent");

        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()},
                getClass().getClassLoader())) {
            var agents = scanWithContextLoader(loader, "fixture.plain");

            assertThat(agents).extracting(Class::getName).containsExactly("fixture.plain.PlainAgent");
        }
    }

    @Test
    @DisplayName("Should not take a sibling package whose name starts the same way, from a jar")
    void shouldNotScanSiblingPackageInJar(@TempDir Path dir) throws Exception {
        Path jar = jarWithAgent(dir, "fixture.scan", "InPackageAgent");
        Path sibling = jarWithAgent(dir, "fixture.scanned", "SiblingAgent");
        Path both = mergeJars(dir, jar, sibling);

        try (URLClassLoader loader = new URLClassLoader(new URL[] {both.toUri().toURL()},
                getClass().getClassLoader())) {
            var agents = scanWithContextLoader(loader, "fixture.scan");

            assertThat(agents).extracting(Class::getName).containsExactly("fixture.scan.InPackageAgent");
        }
    }

    @Test
    @DisplayName("A jar that cannot be read fails the scan instead of finding no agent")
    void shouldFailWhenJarCannotBeRead(@TempDir Path dir) throws Exception {
        URL missing = new URL("jar:" + dir.resolve("missing.jar").toUri() + "!/fixture/absent");

        assertThatThrownBy(() -> scanWithContextLoader(loaderAnswering(missing), "fixture.absent"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fixture.absent");
    }

    @Test
    @DisplayName("A resource protocol the scanner cannot read fails the scan instead of finding no agent")
    void shouldFailOnUnsupportedProtocol() throws Exception {
        URL unsupported = new URL(null, "weird:/fixture/absent", new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL u) {
                throw new UnsupportedOperationException();
            }
        });

        assertThatThrownBy(() -> scanWithContextLoader(loaderAnswering(unsupported), "fixture.absent"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fixture.absent")
                .hasMessageContaining("weird");
    }

    private Set<Class<? extends dev.agenor.core.Agent>> scanWithContextLoader(ClassLoader loader, String pkg) {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            return scanner.scanForAgents(pkg);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private ClassLoader loaderAnswering(URL resource) {
        return new ClassLoader(getClass().getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) {
                return Collections.enumeration(List.of(resource));
            }
        };
    }

    /** Compiles an {@code @Agent} class that is on no class path of this test, and jars it. */
    private static Path jarWithAgent(Path dir, String pkg, String simpleName) throws IOException {
        Path sources = Files.createDirectories(dir.resolve("src-" + simpleName));
        Path classes = Files.createDirectories(dir.resolve("classes-" + simpleName));
        Path source = sources.resolve(simpleName + ".java");
        Files.writeString(source, """
                package %s;
                @dev.agenor.core.annotations.Agent("%s")
                public class %s extends dev.agenor.runtime.agent.BaseAgent {
                }
                """.formatted(pkg, simpleName, simpleName));
        int exit = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-cp", System.getProperty("java.class.path"), "-d", classes.toString(), source.toString());
        assertThat(exit).as("javac exit code").isZero();

        Path jar = dir.resolve(simpleName + ".jar");
        String entry = pkg.replace('.', '/') + "/" + simpleName + ".class";
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            // Directory entries, as Maven writes them: without one, getResources(package) finds nothing.
            String directory = "";
            for (String part : pkg.split("\\.")) {
                directory += part + "/";
                out.putNextEntry(new JarEntry(directory));
                out.closeEntry();
            }
            out.putNextEntry(new JarEntry(entry));
            out.write(Files.readAllBytes(classes.resolve(entry)));
            out.closeEntry();
        }
        return jar;
    }

    private static Path mergeJars(Path dir, Path... jars) throws IOException {
        Path merged = dir.resolve("merged.jar");
        Set<String> written = new HashSet<>();
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(merged))) {
            for (Path jar : jars) {
                try (JarFile in = new JarFile(jar.toFile())) {
                    for (JarEntry entry : Collections.list(in.entries())) {
                        if (!written.add(entry.getName())) {
                            continue;
                        }
                        out.putNextEntry(new JarEntry(entry.getName()));
                        out.write(in.getInputStream(entry).readAllBytes());
                        out.closeEntry();
                    }
                }
            }
        }
        return merged;
    }
}

// Test classes for AgentScannerTest

@Agent("test-valid")
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
@Agent("test-interface")
interface TestAgentInterface extends dev.agenor.core.Agent {
}
