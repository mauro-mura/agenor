package dev.agenor.examples.support.production;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Detects the language of user input.
 * Uses n-gram frequency analysis and keyword matching.
 */
public class LanguageDetector {

    public enum Language {
        ENGLISH("en", "English"),
        ITALIAN("it", "Italiano"),
        SPANISH("es", "Español"),
        FRENCH("fr", "Français"),
        GERMAN("de", "Deutsch"),
        PORTUGUESE("pt", "Português"),
        UNKNOWN("xx", "Unknown");

        private final String code;
        private final String displayName;

        Language(String code, String displayName) {
            this.code = code;
            this.displayName = displayName;
        }

        public String getCode() { return code; }
        public String getDisplayName() { return displayName; }
    }

    // Common words per language (stopwords are good indicators)
    private static final Map<Language, Set<String>> LANGUAGE_MARKERS = Map.of(
        Language.ENGLISH, Set.of(
            "the", "is", "are", "was", "were", "have", "has", "had", "will", "would",
            "can", "could", "should", "what", "where", "when", "how", "why", "who",
            "this", "that", "these", "those", "my", "your", "his", "her", "its",
            "please", "thanks", "thank", "help", "need", "want", "like", "just"
        ),
        Language.ITALIAN, Set.of(
            "il", "la", "lo", "gli", "le", "un", "una", "uno", "che", "non",
            "sono", "sei", "è", "siamo", "siete", "hanno", "avere", "essere",
            "come", "quando", "dove", "perché", "cosa", "chi", "quale",
            "questo", "questa", "questi", "queste", "mio", "mia", "tuo", "tua",
            "grazie", "prego", "aiuto", "vorrei", "posso", "puoi", "ciao", "salve"
        ),
        Language.SPANISH, Set.of(
            "el", "la", "los", "las", "un", "una", "unos", "unas", "que", "no",
            "es", "son", "soy", "eres", "somos", "tienen", "tener", "estar",
            "como", "cuando", "donde", "porque", "qué", "quién", "cuál",
            "este", "esta", "estos", "estas", "mi", "tu", "su",
            "gracias", "por favor", "ayuda", "quiero", "puedo", "hola"
        ),
        Language.FRENCH, Set.of(
            "le", "la", "les", "un", "une", "des", "que", "ne", "pas",
            "est", "sont", "suis", "es", "sommes", "ont", "avoir", "être",
            "comment", "quand", "où", "pourquoi", "quoi", "qui", "quel",
            "ce", "cette", "ces", "mon", "ma", "ton", "ta", "son", "sa",
            "merci", "s'il vous plaît", "aide", "voudrais", "peux", "bonjour"
        ),
        Language.GERMAN, Set.of(
            "der", "die", "das", "ein", "eine", "einer", "dass", "nicht",
            "ist", "sind", "bin", "bist", "haben", "hat", "sein",
            "wie", "wann", "wo", "warum", "was", "wer", "welche",
            "dieser", "diese", "dieses", "mein", "meine", "dein", "deine",
            "danke", "bitte", "hilfe", "möchte", "kann", "kannst", "hallo"
        ),
        Language.PORTUGUESE, Set.of(
            "o", "a", "os", "as", "um", "uma", "uns", "umas", "que", "não",
            "é", "são", "sou", "és", "somos", "têm", "ter", "estar",
            "como", "quando", "onde", "porque", "quê", "quem", "qual",
            "este", "esta", "estes", "estas", "meu", "minha", "teu", "tua",
            "obrigado", "por favor", "ajuda", "quero", "posso", "olá"
        )
    );

    // Character patterns specific to languages
    private static final Map<Language, Pattern> CHAR_PATTERNS = Map.of(
        Language.GERMAN, Pattern.compile("[äöüßÄÖÜ]"),
        Language.FRENCH, Pattern.compile("[àâçéèêëîïôùûüÿœæ]"),
        Language.SPANISH, Pattern.compile("[áéíóúüñ¿¡]"),
        Language.PORTUGUESE, Pattern.compile("[ãõáéíóúàâêôç]"),
        Language.ITALIAN, Pattern.compile("[àèéìíîòóùú]")
    );

    private final Language defaultLanguage;

    public LanguageDetector() {
        this(Language.ENGLISH);
    }

    public LanguageDetector(Language defaultLanguage) {
        this.defaultLanguage = defaultLanguage;
    }

    /**
     * Detects the language of the input text.
     */
    public DetectionResult detect(String text) {
        if (text == null || text.isBlank()) {
            return new DetectionResult(defaultLanguage, 0.0, "Empty input");
        }

        String normalized = text.toLowerCase().trim();

        // Check character patterns first (high confidence indicator)
        for (var entry : CHAR_PATTERNS.entrySet()) {
            if (entry.getValue().matcher(normalized).find()) {
                double confidence = calculateWordConfidence(normalized, entry.getKey());
                if (confidence > 0.2) {
                    return new DetectionResult(entry.getKey(), Math.min(0.95, confidence + 0.3),
                        "Character pattern + word match");
                }
            }
        }

        // Word frequency analysis
        Map<Language, Double> scores = new HashMap<>();
        String[] words = normalized.split("\\s+");

        for (Language lang : LANGUAGE_MARKERS.keySet()) {
            double score = calculateWordConfidence(normalized, lang);
            scores.put(lang, score);
        }

        // Find best match
        Language bestLang = defaultLanguage;
        double bestScore = 0.0;

        for (var entry : scores.entrySet()) {
            if (entry.getValue() > bestScore) {
                bestScore = entry.getValue();
                bestLang = entry.getKey();
            }
        }

        // Require minimum confidence
        if (bestScore < 0.15) {
            return new DetectionResult(defaultLanguage, bestScore, "Low confidence, using default");
        }

        return new DetectionResult(bestLang, bestScore, "Word frequency analysis");
    }

    /**
     * Detects language with context from previous messages.
     */
    public DetectionResult detectWithContext(String text, Language previousLanguage) {
        DetectionResult result = detect(text);

        // If confidence is borderline, prefer previous language
        if (result.confidence() < 0.4 && previousLanguage != Language.UNKNOWN) {
            double prevScore = calculateWordConfidence(text.toLowerCase(), previousLanguage);
            if (prevScore > result.confidence() * 0.7) {
                return new DetectionResult(previousLanguage, prevScore, "Context preference");
            }
        }

        return result;
    }

    private double calculateWordConfidence(String text, Language language) {
        Set<String> markers = LANGUAGE_MARKERS.get(language);
        if (markers == null) return 0.0;

        String[] words = text.split("\\s+");
        if (words.length == 0) return 0.0;

        int matchCount = 0;
        for (String word : words) {
            // Clean punctuation
            String clean = word.replaceAll("[^\\p{L}]", "").toLowerCase();
            if (markers.contains(clean)) {
                matchCount++;
            }
        }

        return (double) matchCount / words.length;
    }

    /**
     * Result of language detection.
     */
    public record DetectionResult(
        Language language,
        double confidence,
        String method
    ) {
        public boolean isConfident() {
            return confidence >= 0.4;
        }

        public String getLanguageCode() {
            return language.getCode();
        }
    }
}
