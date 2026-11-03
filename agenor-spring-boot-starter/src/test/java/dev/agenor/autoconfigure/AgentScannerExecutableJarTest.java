package dev.agenor.autoconfigure;

import dev.agenor.runtime.discovery.AgentScanner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.loader.launch.LaunchedClassLoader;
import org.springframework.boot.loader.net.protocol.Handlers;
import org.springframework.boot.loader.net.protocol.jar.JarUrl;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scans the way an application packaged by {@code spring-boot-maven-plugin} and started with
 * {@code java -jar} does: through Boot's own class loader, whose resources are
 * {@code jar:nested:} URLs that no {@link java.util.jar.JarFile} can open by path. Until 0.40.0
 * the scan threw on them, logged one ERROR, and the runtime started with no agent while health
 * answered {@code UP}. Both places an executable jar keeps classes are covered.
 */
class AgentScannerExecutableJarTest {

    private static final String PACKAGE = "fixture.nested";
    private static final String AGENT = "NestedAgent";
    private static final String CLASS_ENTRY = PACKAGE.replace('.', '/') + "/" + AGENT + ".class";

    @BeforeAll
    static void registerBootUrlHandlers() {
        Handlers.register();
    }

    @Test
    void findsAgentInTheApplicationsOwnClasses(@TempDir Path dir) throws Exception {
        File app = dir.resolve("app.jar").toFile();
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(app.toPath()))) {
            directories(out, "BOOT-INF/classes/");
            out.putNextEntry(new JarEntry("BOOT-INF/classes/" + CLASS_ENTRY));
            out.write(compileAgent(dir));
            out.closeEntry();
        }

        assertScanFinds(JarUrl.create(app, "BOOT-INF/classes/"));
    }

    @Test
    void findsAgentInALibraryNestedInTheJar(@TempDir Path dir) throws Exception {
        ByteArrayOutputStream library = new ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(library)) {
            directories(out, "");
            out.putNextEntry(new JarEntry(CLASS_ENTRY));
            out.write(compileAgent(dir));
            out.closeEntry();
        }

        File app = dir.resolve("app.jar").toFile();
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(app.toPath()))) {
            out.putNextEntry(stored("BOOT-INF/lib/agents.jar", library.toByteArray()));
            out.write(library.toByteArray());
            out.closeEntry();
        }

        assertScanFinds(JarUrl.create(app, "BOOT-INF/lib/agents.jar"));
    }

    private void assertScanFinds(URL nested) throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (LaunchedClassLoader loader =
                     new LaunchedClassLoader(false, new URL[] {nested}, getClass().getClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader);

            Set<Class<? extends dev.agenor.core.Agent>> agents = new AgentScanner().scanForAgents(PACKAGE);

            assertThat(agents).extracting(Class::getName).containsExactly(PACKAGE + "." + AGENT);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    /** Directory entries, as the build writes them: without one, getResources(package) finds nothing. */
    private static void directories(JarOutputStream out, String root) throws IOException {
        String directory = root;
        if (!root.isEmpty()) {
            out.putNextEntry(new JarEntry("BOOT-INF/"));
            out.closeEntry();
            out.putNextEntry(new JarEntry(root));
            out.closeEntry();
        }
        for (String part : PACKAGE.split("\\.")) {
            directory += part + "/";
            out.putNextEntry(new JarEntry(directory));
            out.closeEntry();
        }
    }

    /** A jar nested in a jar has to be stored uncompressed, or Boot cannot read it in place. */
    private static JarEntry stored(String name, byte[] content) {
        JarEntry entry = new JarEntry(name);
        CRC32 crc = new CRC32();
        crc.update(content);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(content.length);
        entry.setCompressedSize(content.length);
        entry.setCrc(crc.getValue());
        return entry;
    }

    /** Compiled here so the class is on no class path of this test: only the jar can provide it. */
    private static byte[] compileAgent(Path dir) throws IOException {
        Path source = dir.resolve(AGENT + ".java");
        Files.writeString(source, """
                package %s;
                @dev.agenor.core.annotations.Agent("nested")
                public class %s extends dev.agenor.runtime.agent.BaseAgent {
                }
                """.formatted(PACKAGE, AGENT));
        Path classes = Files.createDirectories(dir.resolve("classes"));
        int exit = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-cp", System.getProperty("java.class.path"), "-d", classes.toString(), source.toString());
        assertThat(exit).as("javac exit code").isZero();
        return Files.readAllBytes(classes.resolve(CLASS_ENTRY));
    }
}
