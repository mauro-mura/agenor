# Agenor BOM

Bill of Materials for Agenor Framework. Import this BOM to manage Agenor module versions consistently.

## Usage

### Maven

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>dev.agenor</groupId>
            <artifactId>agenor-bom</artifactId>
            <version>0.34.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- No version needed - managed by BOM -->
    <dependency>
        <groupId>dev.agenor</groupId>
        <artifactId>agenor-core</artifactId>
    </dependency>
    
    <dependency>
        <groupId>dev.agenor</groupId>
        <artifactId>agenor-runtime</artifactId>
    </dependency>
</dependencies>
```

### Gradle

```groovy
dependencies {
    implementation platform('dev.agenor:agenor-bom:0.34.0')
    implementation 'dev.agenor:agenor-core'
    implementation 'dev.agenor:agenor-runtime'
}
```

## Modules Managed

All nine, which is every published module — `agenor-examples` is the learning path and is
not published:

- `agenor-core` - Core interfaces and abstractions
- `agenor-runtime` - In-memory runtime implementations
- `agenor-runtime-llm` - LLM-aware runtime pieces (`LLMAgent`, memory, guardrails)
- `agenor-runtime-ext` - Extended runtime (stores, filters, HITL, knowledge, composites)
- `agenor-runtime-scanning` - Classpath scanning and DI-based agent discovery
- `agenor-adapters` - LLM providers, MCP, A2A, Redis messaging
- `agenor-adapters-persistence` - JDBC-backed directory and HITL persistence
- `agenor-spring-boot-starter` - Spring Boot 4.0.x auto-configuration
- `agenor-tools` - CLI

The last four runtime modules were split out of `agenor-runtime` by ADR-027: a consumer with
no LLM, no classpath scanning and no extensions depends on `agenor-core` and
`agenor-runtime` alone.

## Benefits

- **Consistent version management** - All Agenor modules use the same version
- **Simplified dependency declarations** - No need to specify versions for each module
- **Guaranteed module compatibility** - Modules are tested together
- **Easier upgrades** - Update one BOM version to upgrade all modules

## Example Project

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    
    <groupId>com.example</groupId>
    <artifactId>my-agent-app</artifactId>
    <version>1.0.0</version>
    
    <properties>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
    </properties>
    
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>dev.agenor</groupId>
                <artifactId>agenor-bom</artifactId>
                <version>0.34.0</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
    
    <dependencies>
        <dependency>
            <groupId>dev.agenor</groupId>
            <artifactId>agenor-runtime</artifactId>
        </dependency>
    </dependencies>
</project>
```
