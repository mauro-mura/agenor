package dev.jentic.runtime.guardrail;

import dev.jentic.core.guardrail.GuardrailContext;
import dev.jentic.core.guardrail.GuardrailResult;
import dev.jentic.core.guardrail.InputGuardrail;
import dev.jentic.core.guardrail.OutputGuardrail;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects and masks Personally Identifiable Information (PII) in text content.
 *
 * <p>Implements both {@link InputGuardrail} and {@link OutputGuardrail}, so it can be
 * placed in either position of the {@code GuardrailChain}.
 *
 * <p>Each matched PII token is replaced with the literal string {@code [REDACTED]}.
 * Returns {@link GuardrailResult.Modified} when at least one match is found,
 * {@link GuardrailResult.Passed} otherwise.
 *
 * <p>Default constructor enables all {@link PiiType}s. Pass a specific set to the
 * constructor to enable only the desired patterns:
 *
 * <pre>{@code
 * // All PII types (default)
 * new PiiRedactionGuardrail()
 *
 * // Only email and IBAN
 * new PiiRedactionGuardrail(EnumSet.of(PiiType.EMAIL, PiiType.IBAN))
 * }</pre>
 *
 * @since 0.13.0
 */
public class PiiRedactionGuardrail implements InputGuardrail, OutputGuardrail {

    static final String REDACTED = "[REDACTED]";

    // -------------------------------------------------------------------------
    // PII pattern catalogue
    // -------------------------------------------------------------------------

    /**
     * Enumeration of supported PII categories.
     * Each entry encapsulates the compiled {@link Pattern} for that category.
     */
    public enum PiiType {

        /**
         * Standard e-mail addresses. Multi-label domains supported (user@a.b.example.com).
         * No leading \b: the local-part can start after a non-word char (e.g. a dot).
         */
        EMAIL(Pattern.compile(
                "[\\w.+\\-]+@(?:[\\w\\-]+\\.)+[a-zA-Z]{2,}")),

        /**
         * Italian mobile phone numbers.
         * Covers formats: +393xxxxxxxx, +39 3xx xxxxxxx, 3xx.xxxxxxx, 3xxxxxxxxx, etc.
         * Uses negative lookbehind (?<!\d) instead of \b so that the optional +39 prefix
         * (which starts with a non-word char) is also captured correctly.
         */
        PHONE_IT(Pattern.compile(
                "(?<!\\d)(\\+39[.\\s\\-]?)?3\\d{2}[.\\s\\-]?\\d{6,7}\\b")),

        /**
         * Italian Codice Fiscale (fiscal code).
         * 6 alpha + 2 digits + 1 alpha + 2 digits + 1 alpha + 3 digits + 1 alpha (16 chars total).
         */
        CODICE_FISCALE(Pattern.compile(
                "\\b[A-Z]{6}\\d{2}[A-Z]\\d{2}[A-Z]\\d{3}[A-Z]\\b",
                Pattern.CASE_INSENSITIVE)),

        /**
         * IBAN (International Bank Account Number).
         * 2-letter country code + 2 check digits + up to 30 alphanumeric chars.
         */
        IBAN(Pattern.compile(
                "\\b[A-Z]{2}\\d{2}[A-Z0-9]{4}\\d{7}([A-Z0-9]?){0,16}\\b")),

        /**
         * Credit/debit card numbers (13–19 digits, optionally separated by spaces or dashes).
         * Matches Luhn-compatible length ranges used by major card networks.
         */
        CREDIT_CARD(Pattern.compile(
                "\\b(?:\\d[ \\-]?){12,18}\\d\\b"));

        private final Pattern pattern;

        PiiType(Pattern pattern) {
            this.pattern = pattern;
        }

        Pattern pattern() {
            return pattern;
        }
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final Set<PiiType> enabledTypes;

    /** Creates a guardrail with all {@link PiiType}s enabled. */
    public PiiRedactionGuardrail() {
        this.enabledTypes = EnumSet.allOf(PiiType.class);
    }

    /**
     * Creates a guardrail with only the specified {@link PiiType}s enabled.
     *
     * @param enabledTypes the PII categories to detect and redact; must not be empty
     */
    public PiiRedactionGuardrail(Set<PiiType> enabledTypes) {
        if (enabledTypes == null || enabledTypes.isEmpty()) {
            throw new IllegalArgumentException("enabledTypes must not be null or empty");
        }
        this.enabledTypes = EnumSet.copyOf(enabledTypes);
    }

    // -------------------------------------------------------------------------
    // InputGuardrail / OutputGuardrail
    // -------------------------------------------------------------------------

    @Override
    public CompletableFuture<GuardrailResult> apply(String input, GuardrailContext ctx) {
        return CompletableFuture.completedFuture(redact(input));
    }

    // -------------------------------------------------------------------------
    // Core redaction logic
    // -------------------------------------------------------------------------

    /**
     * Applies all enabled PII patterns to {@code text}, replacing every match
     * with {@value #REDACTED}.
     *
     * @param text the content to inspect
     * @return {@link GuardrailResult.Modified} if any PII was found,
     *         {@link GuardrailResult.Passed} otherwise
     */
    GuardrailResult redact(String text) {
        if (text == null || text.isEmpty()) {
            return new GuardrailResult.Passed();
        }

        String current = text;
        boolean modified = false;

        for (PiiType type : enabledTypes) {
            Matcher matcher = type.pattern().matcher(current);
            if (matcher.find()) {
                current = matcher.replaceAll(REDACTED);
                modified = true;
            }
        }

        return modified
                ? new GuardrailResult.Modified(current)
                : new GuardrailResult.Passed();
    }

    /** Returns the set of PII types currently enabled on this instance. */
    public Set<PiiType> enabledTypes() {
        return EnumSet.copyOf(enabledTypes);
    }
}