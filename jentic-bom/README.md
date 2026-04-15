# Jentic BOM

Bill of Materials for Jentic Framework. Import this BOM to manage Jentic module versions consistently.

## Usage

### Maven

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>dev.jentic</groupId>
            <artifactId>jentic-bom</artifactId>
            <version>0.18.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- No version needed - managed by BOM -->
    <dependency>
        <groupId>dev.jentic</groupId>
        <artifactId>jentic-core</artifactId>
    </dependency>
    
    <dependency>
        <groupId>dev.jentic</groupId>
        <artifactId>jentic-runtime</artifactId>
    </dependency>
</dependencies>
```

### Gradle

```groovy
dependencies {
    implementation platform('dev.jentic:jentic-bom:0.14.0-SNAPSHOT')
    implementation 'dev.jentic:jentic-core'
    implementation 'dev.jentic:jentic-runtime'
}
```

## Modules Managed

- `jentic-core` - Core interfaces and abstractions
- `jentic-runtime` - Runtime implementations
- `jentic-adapters` - External system adapters
- `jentic-spring-boot-starter` - Spring Boot 3.5.x auto-configuration
- `jentic-tools` - CLI tools and utilities

## Benefits

- **Consistent version management** - All Jentic modules use the same version
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
                <groupId>dev.jentic</groupId>
                <artifactId>jentic-bom</artifactId>
                <version>0.18.0</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
    
    <dependencies>
        <dependency>
            <groupId>dev.jentic</groupId>
            <artifactId>jentic-runtime</artifactId>
        </dependency>
    </dependencies>
</project>
```
