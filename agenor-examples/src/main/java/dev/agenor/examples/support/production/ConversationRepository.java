package dev.agenor.examples.support.production;

import dev.agenor.examples.support.model.SupportQuery;
import dev.agenor.examples.support.model.SupportResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Repository for persisting conversation data.
 * Uses file-based JSON storage for simplicity (no external DB dependency).
 */
public class ConversationRepository {

    private static final Logger log = LoggerFactory.getLogger(ConversationRepository.class);

    private final Path storageDir;
    private final Map<String, ConversationRecord> cache = new ConcurrentHashMap<>();
    private final boolean persistEnabled;

    public ConversationRepository() {
        this(null); // In-memory only
    }

    public ConversationRepository(Path storageDir) {
        this.storageDir = storageDir;
        this.persistEnabled = storageDir != null;

        if (persistEnabled) {
            try {
                Files.createDirectories(storageDir);
                loadFromDisk();
                log.info("Conversation repository initialized at {}", storageDir);
            } catch (IOException e) {
                log.error("Failed to initialize storage directory", e);
            }
        } else {
            log.info("Conversation repository initialized (in-memory only)");
        }
    }

    /**
     * Saves a conversation turn.
     */
    public void saveTurn(String sessionId, SupportQuery query, SupportResponse response, long responseTimeMs) {
        ConversationRecord record = cache.computeIfAbsent(sessionId,
            id -> new ConversationRecord(id, Instant.now()));

        Turn turn = new Turn(
            Instant.now(),
            query.text(),
            response.text(),
            response.handledBy().code(),
            response.confidence(),
            responseTimeMs
        );

        record.addTurn(turn);
        record.setLastActivity(Instant.now());

        if (persistEnabled) {
            persistRecord(record);
        }
    }

    /**
     * Records user satisfaction feedback.
     */
    public void recordSatisfaction(String sessionId, int rating, String feedback) {
        ConversationRecord record = cache.get(sessionId);
        if (record != null) {
            record.setSatisfactionRating(rating);
            record.setFeedback(feedback);
            record.setLastActivity(Instant.now());

            if (persistEnabled) {
                persistRecord(record);
            }
        }
    }

    /**
     * Records an escalation event.
     */
    public void recordEscalation(String sessionId, String reason) {
        ConversationRecord record = cache.get(sessionId);
        if (record != null) {
            record.setEscalated(true);
            record.setEscalationReason(reason);
            record.setEscalationTime(Instant.now());

            if (persistEnabled) {
                persistRecord(record);
            }
        }
    }

    /**
     * Gets a conversation record by session ID.
     */
    public Optional<ConversationRecord> getBySessionId(String sessionId) {
        return Optional.ofNullable(cache.get(sessionId));
    }

    /**
     * Gets all conversations within a time range.
     */
    public List<ConversationRecord> getByTimeRange(Instant start, Instant end) {
        return cache.values().stream()
            .filter(r -> !r.getCreatedAt().isBefore(start) && !r.getCreatedAt().isAfter(end))
            .sorted(Comparator.comparing(ConversationRecord::getCreatedAt).reversed())
            .collect(Collectors.toList());
    }

    /**
     * Gets recent conversations.
     */
    public List<ConversationRecord> getRecent(int limit) {
        return cache.values().stream()
            .sorted(Comparator.comparing(ConversationRecord::getLastActivity).reversed())
            .limit(limit)
            .collect(Collectors.toList());
    }

    /**
     * Gets all escalated conversations.
     */
    public List<ConversationRecord> getEscalated() {
        return cache.values().stream()
            .filter(ConversationRecord::isEscalated)
            .sorted(Comparator.comparing(ConversationRecord::getEscalationTime).reversed())
            .collect(Collectors.toList());
    }

    /**
     * Returns total conversation count.
     */
    public int count() {
        return cache.size();
    }

    /**
     * Clears all data (for testing).
     */
    public void clear() {
        cache.clear();
        if (persistEnabled) {
            try {
                Files.list(storageDir)
                    .filter(p -> p.toString().endsWith(".json"))
                    .forEach(p -> {
                        try { Files.delete(p); } catch (IOException ignored) {}
                    });
            } catch (IOException ignored) {}
        }
    }

    private void persistRecord(ConversationRecord record) {
        if (!persistEnabled) return;

        Path file = storageDir.resolve(record.getSessionId() + ".json");
        try {
            String json = record.toJson();
            Files.writeString(file, json);
        } catch (IOException e) {
            log.error("Failed to persist conversation {}", record.getSessionId(), e);
        }
    }

    private void loadFromDisk() {
        if (!persistEnabled) return;

        try {
            Files.list(storageDir)
                .filter(p -> p.toString().endsWith(".json"))
                .forEach(p -> {
                    try {
                        String json = Files.readString(p);
                        ConversationRecord record = ConversationRecord.fromJson(json);
                        cache.put(record.getSessionId(), record);
                    } catch (IOException e) {
                        log.warn("Failed to load conversation from {}", p, e);
                    }
                });
            log.info("Loaded {} conversations from disk", cache.size());
        } catch (IOException e) {
            log.error("Failed to list storage directory", e);
        }
    }

    // ========== DATA CLASSES ==========

    /**
     * A single conversation turn.
     */
    public record Turn(
        Instant timestamp,
        String userQuery,
        String agentResponse,
        String intent,
        double confidence,
        long responseTimeMs
    ) {
        public String toJson() {
            return String.format(
                "{\"timestamp\":\"%s\",\"userQuery\":\"%s\",\"agentResponse\":\"%s\"," +
                "\"intent\":\"%s\",\"confidence\":%.2f,\"responseTimeMs\":%d}",
                timestamp, escapeJson(userQuery), escapeJson(agentResponse),
                intent, confidence, responseTimeMs
            );
        }

        private String escapeJson(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r");
        }
    }

    /**
     * Full conversation record.
     */
    public static class ConversationRecord {
        private final String sessionId;
        private final Instant createdAt;
        private Instant lastActivity;
        private final List<Turn> turns = new ArrayList<>();
        private boolean escalated;
        private String escalationReason;
        private Instant escalationTime;
        private int satisfactionRating; // 1-5
        private String feedback;
        private String language;

        public ConversationRecord(String sessionId, Instant createdAt) {
            this.sessionId = sessionId;
            this.createdAt = createdAt;
            this.lastActivity = createdAt;
        }

        public void addTurn(Turn turn) {
            turns.add(turn);
        }

        // Getters and setters
        public String getSessionId() { return sessionId; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getLastActivity() { return lastActivity; }
        public void setLastActivity(Instant lastActivity) { this.lastActivity = lastActivity; }
        public List<Turn> getTurns() { return turns; }
        public boolean isEscalated() { return escalated; }
        public void setEscalated(boolean escalated) { this.escalated = escalated; }
        public String getEscalationReason() { return escalationReason; }
        public void setEscalationReason(String escalationReason) { this.escalationReason = escalationReason; }
        public Instant getEscalationTime() { return escalationTime; }
        public void setEscalationTime(Instant escalationTime) { this.escalationTime = escalationTime; }
        public int getSatisfactionRating() { return satisfactionRating; }
        public void setSatisfactionRating(int satisfactionRating) { this.satisfactionRating = satisfactionRating; }
        public String getFeedback() { return feedback; }
        public void setFeedback(String feedback) { this.feedback = feedback; }
        public String getLanguage() { return language; }
        public void setLanguage(String language) { this.language = language; }

        public int getTurnCount() { return turns.size(); }

        public long getAverageResponseTime() {
            if (turns.isEmpty()) return 0;
            return (long) turns.stream()
                .mapToLong(Turn::responseTimeMs)
                .average()
                .orElse(0);
        }

        public String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"sessionId\":\"").append(sessionId).append("\",");
            sb.append("\"createdAt\":\"").append(createdAt).append("\",");
            sb.append("\"lastActivity\":\"").append(lastActivity).append("\",");
            sb.append("\"escalated\":").append(escalated).append(",");
            if (escalationReason != null) {
                sb.append("\"escalationReason\":\"").append(escalationReason).append("\",");
            }
            if (escalationTime != null) {
                sb.append("\"escalationTime\":\"").append(escalationTime).append("\",");
            }
            sb.append("\"satisfactionRating\":").append(satisfactionRating).append(",");
            if (feedback != null) {
                sb.append("\"feedback\":\"").append(feedback).append("\",");
            }
            if (language != null) {
                sb.append("\"language\":\"").append(language).append("\",");
            }
            sb.append("\"turns\":[");
            sb.append(turns.stream().map(Turn::toJson).collect(Collectors.joining(",")));
            sb.append("]}");
            return sb.toString();
        }

        public static ConversationRecord fromJson(String json) {
            // Simple parsing - in production use a proper JSON library
            String sessionId = extractJsonString(json, "sessionId");
            Instant createdAt = Instant.parse(extractJsonString(json, "createdAt"));
            ConversationRecord record = new ConversationRecord(sessionId, createdAt);

            String lastActivity = extractJsonString(json, "lastActivity");
            if (lastActivity != null) {
                record.setLastActivity(Instant.parse(lastActivity));
            }

            record.setEscalated(json.contains("\"escalated\":true"));
            record.setEscalationReason(extractJsonString(json, "escalationReason"));

            String escalationTime = extractJsonString(json, "escalationTime");
            if (escalationTime != null && !escalationTime.isEmpty()) {
                record.setEscalationTime(Instant.parse(escalationTime));
            }

            String rating = extractJsonValue(json, "satisfactionRating");
            if (rating != null && !rating.isEmpty()) {
                record.setSatisfactionRating(Integer.parseInt(rating));
            }

            record.setFeedback(extractJsonString(json, "feedback"));
            record.setLanguage(extractJsonString(json, "language"));

            return record;
        }

        private static String extractJsonString(String json, String key) {
            String pattern = "\"" + key + "\":\"";
            int start = json.indexOf(pattern);
            if (start < 0) return null;
            start += pattern.length();
            int end = json.indexOf("\"", start);
            if (end < 0) return null;
            return json.substring(start, end);
        }

        private static String extractJsonValue(String json, String key) {
            String pattern = "\"" + key + "\":";
            int start = json.indexOf(pattern);
            if (start < 0) return null;
            start += pattern.length();
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.')) {
                end++;
            }
            return json.substring(start, end);
        }
    }
}
