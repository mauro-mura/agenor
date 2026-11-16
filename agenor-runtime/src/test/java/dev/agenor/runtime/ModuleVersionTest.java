package dev.agenor.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ModuleVersion")
class ModuleVersionTest {

    private static final ModuleVersion RUNTIME = new ModuleVersion("Agenor Runtime", "0.42.0");

    @Test
    @DisplayName("a class loaded from a directory has no version")
    void classFromDirectory_isEmpty() {
        // Surefire runs this module from target/classes, where no manifest exists
        assertThat(ModuleVersion.of(AgenorRuntime.class)).isEmpty();
    }

    @Test
    @DisplayName("a class loaded from a jar reports the title and version its manifest declares")
    void classFromJar_readsManifest(@TempDir Path dir) throws Exception {
        Path jar = jarContaining(Probe.class, dir.resolve("probe.jar"),
                "Agenor Runtime LLM", "0.39.0");

        // No parent loader: the probe must come from the jar, not from target/test-classes
        try (var loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
            Class<?> probe = loader.loadClass(Probe.class.getName());

            assertThat(ModuleVersion.of(probe))
                    .contains(new ModuleVersion("Agenor Runtime LLM", "0.39.0"));
        }
    }

    @Test
    @DisplayName("a class with no version is described by its package, so it can be reported")
    void unversionedClass_describedByPackage() {
        var module = ModuleVersion.ofOrUnversioned(AgenorRuntime.class);

        assertThat(module).isEqualTo(
                new ModuleVersion("dev.agenor.runtime", ModuleVersion.UNVERSIONED));
        assertThat(ModuleVersion.differingFrom(Optional.of(RUNTIME), List.of(module)))
                .containsExactly(module);
    }

    @Test
    @DisplayName("modules at the runtime's version are not reported")
    void sameVersion_notReported() {
        var modules = List.of(new ModuleVersion("Agenor Runtime LLM", "0.42.0"));

        assertThat(ModuleVersion.differingFrom(Optional.of(RUNTIME), modules)).isEmpty();
    }

    @Test
    @DisplayName("a module at another version is reported once, however many classes it supplied")
    void otherVersion_reportedOncePerModule() {
        var llm = new ModuleVersion("Agenor Runtime LLM", "0.39.0");
        var modules = List.of(llm, llm, new ModuleVersion("Agenor Runtime Extensions", "0.42.0"));

        assertThat(ModuleVersion.differingFrom(Optional.of(RUNTIME), modules)).containsExactly(llm);
    }

    @Test
    @DisplayName("an unknown runtime version reports nothing, having nothing to compare against")
    void unknownRuntime_reportsNothing() {
        var modules = List.of(new ModuleVersion("Agenor Runtime LLM", "0.39.0"));

        assertThat(ModuleVersion.differingFrom(Optional.empty(), modules)).isEmpty();
    }

    private static Path jarContaining(Class<?> type, Path jar, String title, String version)
            throws IOException {
        var manifest = new Manifest();
        Attributes main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.put(Attributes.Name.IMPLEMENTATION_TITLE, title);
        main.put(Attributes.Name.IMPLEMENTATION_VERSION, version);

        String entry = type.getName().replace('.', '/') + ".class";
        try (OutputStream out = Files.newOutputStream(jar);
             var jarOut = new JarOutputStream(out, manifest);
             InputStream bytes = type.getClassLoader().getResourceAsStream(entry)) {
            jarOut.putNextEntry(new JarEntry(entry));
            bytes.transferTo(jarOut);
            jarOut.closeEntry();
        }
        return jar;
    }

    /** Depends on nothing but {@code Object}, so a loader with no parent can define it. */
    public static final class Probe {
    }
}
