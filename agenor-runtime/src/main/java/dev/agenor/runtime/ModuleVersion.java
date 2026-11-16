package dev.agenor.runtime;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The name and version of the Agenor jar a class was loaded from, as its manifest declares them.
 *
 * <p>Every Agenor jar carries {@code Implementation-Title} and {@code Implementation-Version}
 * (the parent POM turns on the jar plugin's default implementation entries), so the version is
 * read rather than kept in a file the release has to bump. A class loaded from a directory
 * instead of a jar — tests, an IDE run, {@code mvn exec} inside the reactor — has no manifest,
 * and {@link #of(Class)} is then empty: the runtime says it does not know rather than guess.
 *
 * <p>Package-private on purpose: the startup log is the only reader, and a public accessor with
 * no user would be surface nothing asked for.
 */
record ModuleVersion(String title, String version) {

    ModuleVersion {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(version, "version");
    }

    /**
     * Reads the manifest entries of the jar {@code type} was loaded from.
     *
     * @return empty when the class did not come from a jar declaring both entries
     */
    static Optional<ModuleVersion> of(Class<?> type) {
        Package pkg = type.getPackage();
        if (pkg == null || pkg.getImplementationVersion() == null) {
            return Optional.empty();
        }
        String title = pkg.getImplementationTitle() != null
                ? pkg.getImplementationTitle()
                : pkg.getName();
        return Optional.of(new ModuleVersion(title, pkg.getImplementationVersion()));
    }

    /**
     * Like {@link #of(Class)}, but a class with no version is still described, by its package,
     * so that it can be reported. A jar released before the manifest carried a version has none,
     * and it is the likeliest thing to sit next to a newer runtime.
     */
    static ModuleVersion ofOrUnversioned(Class<?> type) {
        return of(type).orElseGet(() -> new ModuleVersion(type.getPackageName(), UNVERSIONED));
    }

    /** What {@link #ofOrUnversioned(Class)} reports for a class whose jar declares no version. */
    static final String UNVERSIONED =
            "(no version in its manifest: an older release, or not loaded from a jar)";

    /**
     * The modules whose version differs from the runtime's, one entry per module however many
     * of its classes were resolved.
     *
     * <p>An unknown runtime version compares with nothing: without it there is no reference to
     * call a module skewed against.
     */
    static List<ModuleVersion> differingFrom(Optional<ModuleVersion> runtime,
                                             Collection<ModuleVersion> modules) {
        if (runtime.isEmpty()) {
            return List.of();
        }
        String expected = runtime.get().version();
        Map<String, ModuleVersion> byTitle = new LinkedHashMap<>();
        for (ModuleVersion module : modules) {
            if (!module.version().equals(expected)) {
                byTitle.putIfAbsent(module.title(), module);
            }
        }
        return List.copyOf(byTitle.values());
    }

    @Override
    public String toString() {
        return title + " " + version;
    }
}
