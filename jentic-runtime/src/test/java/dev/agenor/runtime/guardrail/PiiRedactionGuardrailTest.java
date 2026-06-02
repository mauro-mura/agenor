package dev.agenor.runtime.guardrail;

import dev.agenor.core.guardrail.GuardrailContext;
import dev.agenor.core.guardrail.GuardrailResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static dev.agenor.runtime.guardrail.PiiRedactionGuardrail.REDACTED;
import static org.assertj.core.api.Assertions.*;

@DisplayName("PiiRedactionGuardrail")
class PiiRedactionGuardrailTest {

    private static final GuardrailContext CTX = GuardrailContext.of("test-agent");
    private final PiiRedactionGuardrail guardrail = new PiiRedactionGuardrail();

    // -------------------------------------------------------------------------
    // EMAIL
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("EMAIL")
    class EmailTests {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                "mario.rossi@example.com",
                "user+tag@sub.domain.org",
                "first.last@company.it",
                "no-reply@agenor.dev",
                "a@b.co",
                "test123@test-domain.net",
                "UPPER@CASE.COM",
                "user_name@host.io",
                "x.y+z@mail.a.b.example.com",
                "simple@example.com"
        })
        @DisplayName("detects and redacts email address")
        void detectsEmail(String input) {
            GuardrailResult result = guardrail.redact("Contact me at " + input + " please.");
            assertThat(result).isInstanceOf(GuardrailResult.Modified.class);
            assertThat(((GuardrailResult.Modified) result).newContent()).doesNotContain(input);
            assertThat(((GuardrailResult.Modified) result).newContent()).contains(REDACTED);
        }
    }

    // -------------------------------------------------------------------------
    // PHONE_IT
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("PHONE_IT")
    class PhoneItTests {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                "3331234567",
                "+39 333 1234567",
                "+393331234567",
                "333 1234567",
                "347 1234567",
                "366.1234567",
                "3201234567",
                "+39 320 1234567",
                "3471234567",
                "391 1234567"
        })
        @DisplayName("detects and redacts Italian phone number")
        void detectsPhoneIt(String phone) {
            GuardrailResult result = guardrail.redact("Call me at " + phone + ".");
            assertThat(result).isInstanceOf(GuardrailResult.Modified.class);
        }
    }

    // -------------------------------------------------------------------------
    // CODICE FISCALE
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("CODICE_FISCALE")
    class CodiceFiscaleTests {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                "RSSMRA85M01H501Z",
                "BNCFNC72A01F205X",
                "VRDGNN60D10A944S",
                "MRORSS80E41H501W",
                "PRTLNZ95P54L219U",
                "CRLLCU85A01C573Y",
                "BNCLCA72A01F205X",
                "STFNTN90E60F205Z",
                "rssmra85m01h501z",   // lowercase must also match (CASE_INSENSITIVE)
                "GLLGPP85T10H501B"
        })
        @DisplayName("detects and redacts Italian codice fiscale")
        void detectsCodiceFiscale(String cf) {
            GuardrailResult result = guardrail.redact("CF: " + cf);
            assertThat(result).isInstanceOf(GuardrailResult.Modified.class);
            assertThat(((GuardrailResult.Modified) result).newContent()).doesNotContainIgnoringCase(cf);
        }
    }

    // -------------------------------------------------------------------------
    // IBAN
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("IBAN")
    class IbanTests {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                "IT60X0542811101000000123456",
                "DE89370400440532013000",
                "GB29NWBK60161331926819",
                "FR7630006000011234567890189",
                "ES9121000418450200051332",
                "NL91ABNA0417164300",
                "BE68539007547034",
                "AT611904300234573201",
                "CH9300762011623852957",
                "PL61109010140000071219812874"
        })
        @DisplayName("detects and redacts IBAN")
        void detectsIban(String iban) {
            GuardrailResult result = guardrail.redact("Pay to IBAN " + iban + ".");
            assertThat(result).isInstanceOf(GuardrailResult.Modified.class);
        }
    }

    // -------------------------------------------------------------------------
    // CREDIT CARD
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("CREDIT_CARD")
    class CreditCardTests {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                "4111111111111111",       // Visa 16
                "5500005555555559",       // Mastercard 16
                "371449635398431",        // Amex 15
                "6011111111111117",       // Discover 16
                "4111 1111 1111 1111",    // spaced Visa
                "5500-0055-5555-5559",    // dashed Mastercard
                "3714 496353 98431",      // spaced Amex
                "4012888888881881",       // Visa 16
                "4222222222222",          // Visa 13
                "4111111111111111111"     // Visa 19
        })
        @DisplayName("detects and redacts credit card number")
        void detectsCreditCard(String cc) {
            GuardrailResult result = guardrail.redact("Card: " + cc);
            assertThat(result).isInstanceOf(GuardrailResult.Modified.class);
        }
    }

    // -------------------------------------------------------------------------
    // Multi-PII in single string
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Multiple PII in one string")
    class MultiPiiTests {

        @Test
        @DisplayName("email and IBAN in same string — both redacted")
        void emailAndIban_bothRedacted() {
            String input = "Email mario@example.com, IBAN IT60X0542811101000000123456";
            GuardrailResult result = guardrail.redact(input);
            assertThat(result).isInstanceOf(GuardrailResult.Modified.class);
            String out = ((GuardrailResult.Modified) result).newContent();
            assertThat(out).doesNotContain("mario@example.com");
            assertThat(out).doesNotContain("IT60X0542811101000000123456");
        }

        @Test
        @DisplayName("Modified content does not contain any original PII token")
        void modifiedContent_containsNoOriginalPii() {
            String email = "user@company.it";
            String cf    = "RSSMRA85M01H501Z";
            String input = "User " + email + " with CF " + cf;
            GuardrailResult result = guardrail.redact(input);
            assertThat(result).isInstanceOf(GuardrailResult.Modified.class);
            String out = ((GuardrailResult.Modified) result).newContent();
            assertThat(out).doesNotContain(email);
            assertThat(out).doesNotContainIgnoringCase(cf);
        }
    }

    // -------------------------------------------------------------------------
    // Clean text — false positive check (< 5%)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("False positive rate on clean text")
    class FalsePositiveTests {

        private static final String[] CLEAN_TEXTS = {
                "The weather today is sunny and warm.",
                "Please review the attached document before the meeting.",
                "Our quarterly revenue grew by 12% compared to last year.",
                "Java 21 introduces virtual threads and sealed classes.",
                "The product ships within 3 to 5 business days.",
                "Contact the support team for further assistance.",
                "Version 2.0 includes performance improvements and bug fixes.",
                "The conference will be held in Milan next October.",
                "Please update your password regularly for security.",
                "The project deadline has been moved to next Friday.",
                "All team members should attend the sprint review.",
                "The API accepts JSON payloads up to 1 MB in size.",
                "Our SLA guarantees 99.9% uptime for all services.",
                "Ensure the database indexes are optimized for large queries.",
                "The new feature was merged into the main branch yesterday.",
                "Testing coverage has improved to 85% after refactoring.",
                "Documentation updates are required before the release.",
                "The deployment pipeline runs on every pull request.",
                "Code review comments should be addressed within 48 hours.",
                "The Jentic framework supports multiple LLM providers."
        };

        @Test
        @DisplayName("false positive rate < 5% on clean text corpus")
        void falsePositiveRate_belowThreshold() {
            int falsePositives = 0;
            for (String text : CLEAN_TEXTS) {
                GuardrailResult result = guardrail.redact(text);
                if (result instanceof GuardrailResult.Modified) {
                    falsePositives++;
                }
            }

            double rate = (double) falsePositives / CLEAN_TEXTS.length;
            assertThat(rate)
                    .as("False positive rate %.1f%% exceeds 5%% threshold", rate * 100)
                    .isLessThan(0.05);
        }
    }

    // -------------------------------------------------------------------------
    // Configurable PII types
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Configurable PiiType set")
    class ConfigurableTypesTests {

        @Test
        @DisplayName("only EMAIL enabled — phone not redacted")
        void onlyEmail_phoneNotRedacted() {
            var g = new PiiRedactionGuardrail(EnumSet.of(PiiRedactionGuardrail.PiiType.EMAIL));
            GuardrailResult result = g.redact("Call 3331234567 or email user@host.com");
            assertThat(result).isInstanceOf(GuardrailResult.Modified.class);
            String out = ((GuardrailResult.Modified) result).newContent();
            assertThat(out).contains("3331234567");      // phone not redacted
            assertThat(out).doesNotContain("user@host.com");
        }

        @Test
        @DisplayName("null enabledTypes throws IllegalArgumentException")
        void nullTypes_throws() {
            assertThatThrownBy(() -> new PiiRedactionGuardrail(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("empty enabledTypes throws IllegalArgumentException")
        void emptyTypes_throws() {
            assertThatThrownBy(() -> new PiiRedactionGuardrail(EnumSet.noneOf(PiiRedactionGuardrail.PiiType.class)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("enabledTypes() returns a defensive copy")
        void enabledTypes_defensiveCopy() {
            var g = new PiiRedactionGuardrail();
            Set<PiiRedactionGuardrail.PiiType> returned = g.enabledTypes();
            returned.clear(); // modifying returned set must not affect the guardrail
            assertThat(g.enabledTypes()).isNotEmpty();
        }
    }

    // -------------------------------------------------------------------------
    // CompletableFuture contract (InputGuardrail / OutputGuardrail)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Async contract")
    class AsyncContractTests {

        @Test
        @DisplayName("apply() as InputGuardrail returns Modified future for PII input")
        void inputGuardrail_returnsFuture() throws Exception {
            CompletableFuture<GuardrailResult> future =
                    guardrail.apply("email me at test@example.com", CTX);
            assertThat(future).isNotNull();
            assertThat(future.get()).isInstanceOf(GuardrailResult.Modified.class);
        }

        @Test
        @DisplayName("apply() as OutputGuardrail returns Passed future for clean output")
        void outputGuardrail_passedFuture() throws Exception {
            CompletableFuture<GuardrailResult> future =
                    guardrail.apply("The meeting is on Monday.", CTX);
            assertThat(future).isNotNull();
            assertThat(future.get()).isInstanceOf(GuardrailResult.Passed.class);
        }
    }
}
