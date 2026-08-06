package dev.agenor.runtime.discovery;

import dev.agenor.core.annotations.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Scanner for discovering classes annotated with @Agent.
 * Supports both file system and JAR-based class scanning.
 */
public class AgentScanner {

    private static final Logger log = LoggerFactory.getLogger(AgentScanner.class);

    /**
     * Scan for agent classes in the specified packages
     *
     * @param packageNames packages to scan
     * @return set of classes annotated with @Agent
     */
    public Set<Class<? extends dev.agenor.core.Agent>> scanForAgents(String... packageNames) {
        Set<Class<? extends dev.agenor.core.Agent>> agentClasses = new HashSet<>();

        for (String packageName : packageNames) {
            if (packageName == null || packageName.trim().isEmpty()) {
                continue;
            }

            log.debug("Scanning package for agents: {}", packageName);

            try {
                Set<Class<?>> classes = scanPackage(packageName);

                for (Class<?> clazz : classes) {
                    if (isAgentClass(clazz)) {
                        @SuppressWarnings("unchecked")
                        Class<? extends dev.agenor.core.Agent> agentClass = (Class<? extends dev.agenor.core.Agent>) clazz;
                        agentClasses.add(agentClass);
                        log.info("Discovered agent class: {}", clazz.getName());
                    }
                }

            } catch (Exception e) {
                log.error("Error scanning package: {}", packageName, e);
            }
        }

        log.info("Agent discovery completed. Found {} agent classes", agentClasses.size());
        return agentClasses;
    }

    /**
     * Check if a class is a valid agent class
     */
    private boolean isAgentClass(Class<?> clazz) {
        return clazz.isAnnotationPresent(Agent.class) &&
               dev.agenor.core.Agent.class.isAssignableFrom(clazz) &&
               !clazz.isInterface();
    }

    /**
     * Scan all classes in a package
     */
    private Set<Class<?>> scanPackage(String packageName) throws IOException, ClassNotFoundException {
        Set<Class<?>> classes = new HashSet<>();
        String packagePath = packageName.replace('.', '/');

        // Try multiple classloaders
        ClassLoader[] loaders = {
            Thread.currentThread().getContextClassLoader(),
            getClass().getClassLoader(),
            ClassLoader.getSystemClassLoader()
        };

        for (ClassLoader classLoader : loaders) {
        	if (classLoader == null) continue;

        	Enumeration<URL> resources = classLoader.getResources(packagePath);

        	if (!resources.hasMoreElements()) {
                log.debug("No resources found for package {} with classloader {}",
                          packageName, classLoader.getClass().getName());
                continue;
            }

        	while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                String protocol = resource.getProtocol();

                if ("file".equals(protocol)) {
                    // File system scanning
                    classes.addAll(scanFileSystem(resource, packageName));
                } else if ("jar".equals(protocol)) {
                    // JAR file scanning
                    classes.addAll(scanJarFile(resource, packagePath));
                } else {
                    log.warn("Unsupported resource protocol for package scanning: {}", protocol);
                }
            }

        	if (!classes.isEmpty()) break; // Found classes, stop trying
        }

        return classes;
    }

    /**
     * Scan classes from file system
     */
    private Set<Class<?>> scanFileSystem(URL resource, String packageName) throws ClassNotFoundException {
        Set<Class<?>> classes = new HashSet<>();

        try {
            String path = URLDecoder.decode(resource.getFile(), StandardCharsets.UTF_8);
            File directory = new File(path);

            if (directory.exists() && directory.isDirectory()) {
                classes.addAll(findClassesInDirectory(directory, packageName));
            }
        } catch (Exception e) {
            log.error("Error scanning file system for package: {}", packageName, e);
        }

        return classes;
    }

    /**
     * Recursively find classes in directory
     */
    private Set<Class<?>> findClassesInDirectory(File directory, String packageName) throws ClassNotFoundException {
        Set<Class<?>> classes = new HashSet<>();

        File[] files = directory.listFiles();
        if (files == null) {
            return classes;
        }

        for (File file : files) {
            String fileName = file.getName();

            if (file.isDirectory()) {
                // Recursive scan of subdirectories
                String subPackageName = packageName + "." + fileName;
                classes.addAll(findClassesInDirectory(file, subPackageName));
            } else if (fileName.endsWith(".class") && !fileName.contains("$")) {
                // Load class (skip inner classes for now)
                String className = packageName + "." + fileName.substring(0, fileName.length() - 6);

                try {
                    Class<?> clazz = Class.forName(className);
                    classes.add(clazz);
                    log.trace("Found class: {}", className);
                } catch (ClassNotFoundException e) {
                    log.debug("Could not load class: {}", className, e);
                } catch (NoClassDefFoundError e) {
                    log.debug("Missing dependency for class: {}", className, e);
                }
            }
        }

        return classes;
    }

    /**
     * Scan classes from JAR file
     */
    private Set<Class<?>> scanJarFile(URL resource, String packagePath) throws IOException, ClassNotFoundException {
        Set<Class<?>> classes = new HashSet<>();

        // Extract JAR file path from URL
        String jarPath = resource.getPath();
        if (jarPath.startsWith("file:")) {
            jarPath = jarPath.substring(5);
        }

        int separatorIndex = jarPath.indexOf("!");
        if (separatorIndex > 0) {
            jarPath = jarPath.substring(0, separatorIndex);
        }

        jarPath = URLDecoder.decode(jarPath, StandardCharsets.UTF_8);

        try (JarFile jarFile = new JarFile(jarPath)) {
            Enumeration<JarEntry> entries = jarFile.entries();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();

                if (entryName.startsWith(packagePath) &&
                    entryName.endsWith(".class") &&
                    !entryName.contains("$")) {

                    String className = entryName.replace('/', '.').substring(0, entryName.length() - 6);

                    try {
                        Class<?> clazz = Class.forName(className);
                        classes.add(clazz);
                        log.trace("Found class in JAR: {}", className);
                    } catch (ClassNotFoundException e) {
                        log.debug("Could not load class from JAR: {}", className, e);
                    } catch (NoClassDefFoundError e) {
                        log.debug("Missing dependency for JAR class: {}", className, e);
                    }
                }
            }
        }

        return classes;
    }
}
