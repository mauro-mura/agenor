package dev.jentic.core.memory;

import java.time.Duration;
import java.time.Instant;

/**
 * Statistics and metrics about memory usage.
 * 
 * <p>Example usage:
 * <pre>{@code
 * MemoryStats stats = memoryManager.getStats();
 * 
 * System.out.println("Short-term entries: " + stats.shortTermCount());
 * System.out.println("Long-term entries: " + stats.longTermCount());
 * System.out.println("Total tokens: " + stats.estimatedTokens());
 * System.out.println("Last updated: " + stats.lastUpdated());
 * }</pre>
 * 
 * @param shortTermCount    number of entries in {@link MemoryScope#SHORT_TERM}
 * @param longTermCount     number of entries in {@link MemoryScope#LONG_TERM}
 * @param estimatedTokens   approximate total LLM token count across all entries
 * @param lastUpdated       instant at which these statistics were last computed
 * @param estimatedSizeBytes approximate total serialised size in bytes
 * @since 0.6.0
 */
public record MemoryStats(
    int shortTermCount,
    int longTermCount,
    int estimatedTokens,
    Instant lastUpdated,
    long estimatedSizeBytes
) {
    
    /**
     * Compact constructor with validation.
     */
    public MemoryStats {
        if (shortTermCount < 0) {
            throw new IllegalArgumentException("Short-term count cannot be negative");
        }
        if (longTermCount < 0) {
            throw new IllegalArgumentException("Long-term count cannot be negative");
        }
        if (estimatedTokens < 0) {
            throw new IllegalArgumentException("Estimated tokens cannot be negative");
        }
        if (estimatedSizeBytes < 0) {
            throw new IllegalArgumentException("Estimated size cannot be negative");
        }
        if (lastUpdated == null) {
            lastUpdated = Instant.now();
        }
    }
    
    /**
     * Gets the total number of memory entries across all scopes.
     * 
     * @return total entry count
     */
    public int totalCount() {
        return shortTermCount + longTermCount;
    }
    
    /**
     * Checks if the memory store is empty.
     * 
     * @return true if no entries exist
     */
    public boolean isEmpty() {
        return totalCount() == 0;
    }
    
    /**
     * Gets the age of these statistics.
     * 
     * @return duration since last update
     */
    public Duration age() {
        return Duration.between(lastUpdated, Instant.now());
    }
    
    /**
     * Checks if these statistics are stale (older than threshold).
     * 
     * @param threshold the staleness threshold
     * @return true if statistics are older than threshold
     */
    public boolean isStale(Duration threshold) {
        return age().compareTo(threshold) > 0;
    }
    
    /**
     * Gets estimated size in kilobytes.
     * 
     * @return size in KB
     */
    public double estimatedSizeKB() {
        return estimatedSizeBytes / 1024.0;
    }
    
    /**
     * Gets estimated size in megabytes.
     * 
     * @return size in MB
     */
    public double estimatedSizeMB() {
        return estimatedSizeBytes / (1024.0 * 1024.0);
    }
    
    /**
     * Creates a MemoryStats instance with zero values.
     * 
     * @return empty statistics
     */
    public static MemoryStats empty() {
        return new MemoryStats(0, 0, 0, Instant.now(), 0);
    }
    
    /**
     * Creates a builder for constructing MemoryStats instances.
     * 
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Builder for creating MemoryStats instances.
     */
    public static class Builder {
        private int shortTermCount = 0;
        private int longTermCount = 0;
        private int estimatedTokens = 0;
        private Instant lastUpdated = Instant.now();
        private long estimatedSizeBytes = 0;
        
        public Builder shortTermCount(int count) {
            this.shortTermCount = count;
            return this;
        }
        
        public Builder longTermCount(int count) {
            this.longTermCount = count;
            return this;
        }
        
        public Builder estimatedTokens(int tokens) {
            this.estimatedTokens = tokens;
            return this;
        }
        
        public Builder lastUpdated(Instant timestamp) {
            this.lastUpdated = timestamp;
            return this;
        }
        
        public Builder estimatedSizeBytes(long bytes) {
            this.estimatedSizeBytes = bytes;
            return this;
        }
        
        public MemoryStats build() {
            return new MemoryStats(
                shortTermCount,
                longTermCount,
                estimatedTokens,
                lastUpdated,
                estimatedSizeBytes
            );
        }
    }
}
