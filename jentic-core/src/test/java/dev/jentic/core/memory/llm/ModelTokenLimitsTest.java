package dev.jentic.core.memory.llm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for ModelTokenLimits registry.
 *
 * <p>These tests cover only registry behaviour (register, lookup, unregister,
 * validation). Vendor-specific model names are NOT tested here — each adapter
 * module is responsible for testing its own registrations.
 */
class ModelTokenLimitsTest {

    // Unique prefix avoids collisions with adapter-registered names in the
    // shared static registry when tests run in the same JVM.
    private static final String PREFIX = "test-model-registry-";

    @AfterEach
    void cleanup() {
        ModelTokenLimits.unregister(PREFIX + "a");
        ModelTokenLimits.unregister(PREFIX + "b");
        ModelTokenLimits.unregister(PREFIX + "override");
        ModelTokenLimits.unregister(PREFIX + "case");
        ModelTokenLimits.unregister(PREFIX + "trim");
    }

    // ========== REGISTRY LOOKUP ==========

    @Test
    void getLimit_shouldReturnRegisteredLimit() {
        ModelTokenLimits.register(PREFIX + "a", 32_768);

        assertThat(ModelTokenLimits.getLimit(PREFIX + "a")).isEqualTo(32_768);
    }

    @Test
    void getLimit_shouldReturnDefaultForUnknownModel() {
        assertThat(ModelTokenLimits.getLimit("completely-unknown-model-xyz"))
            .isEqualTo(ModelTokenLimits.DEFAULT_LIMIT)
            .isEqualTo(4_096);
    }

    @Test
    void getLimit_shouldThrowForNullModel() {
        assertThatThrownBy(() -> ModelTokenLimits.getLimit(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Model cannot be null");
    }

    // ========== NORMALISATION ==========

    @Test
    void getLimit_shouldBeCaseInsensitive() {
        ModelTokenLimits.register(PREFIX + "case", 8_192);

        assertThat(ModelTokenLimits.getLimit(PREFIX + "case")).isEqualTo(8_192);
        assertThat(ModelTokenLimits.getLimit((PREFIX + "case").toUpperCase())).isEqualTo(8_192);
        assertThat(ModelTokenLimits.getLimit("Test-Model-Registry-Case")).isEqualTo(8_192);
    }

    @Test
    void getLimit_shouldTrimWhitespace() {
        ModelTokenLimits.register(PREFIX + "trim", 16_384);

        assertThat(ModelTokenLimits.getLimit("  " + PREFIX + "trim  ")).isEqualTo(16_384);
    }

    // ========== PREFIX MATCH ==========

    @Test
    void getLimit_shouldResolveByPrefixMatch() {
        ModelTokenLimits.register(PREFIX + "a", 128_000);

        // Versioned alias that starts with the registered key
        assertThat(ModelTokenLimits.getLimit(PREFIX + "a-20250101")).isEqualTo(128_000);
    }

    // ========== REGISTER / OVERRIDE ==========

    @Test
    void register_shouldOverrideExistingLimit() {
        ModelTokenLimits.register(PREFIX + "override", 1_000);
        ModelTokenLimits.register(PREFIX + "override", 2_000);

        assertThat(ModelTokenLimits.getLimit(PREFIX + "override")).isEqualTo(2_000);
    }

    @Test
    void register_shouldThrowForNullModel() {
        assertThatThrownBy(() -> ModelTokenLimits.register(null, 1_000))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Model cannot be null or empty");
    }

    @Test
    void register_shouldThrowForBlankModel() {
        assertThatThrownBy(() -> ModelTokenLimits.register("   ", 1_000))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void register_shouldThrowForNonPositiveLimit() {
        assertThatThrownBy(() -> ModelTokenLimits.register(PREFIX + "b", 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Limit must be positive");
        assertThatThrownBy(() -> ModelTokenLimits.register(PREFIX + "b", -1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ========== UNREGISTER ==========

    @Test
    void unregister_shouldRemoveModel() {
        ModelTokenLimits.register(PREFIX + "a", 32_768);
        ModelTokenLimits.unregister(PREFIX + "a");

        assertThat(ModelTokenLimits.hasModel(PREFIX + "a")).isFalse();
        assertThat(ModelTokenLimits.getLimit(PREFIX + "a")).isEqualTo(ModelTokenLimits.DEFAULT_LIMIT);
    }

    @Test
    void unregister_shouldBeNoOpForUnknownModel() {
        assertThatNoException().isThrownBy(() -> ModelTokenLimits.unregister("never-registered-xyz"));
    }

    @Test
    void unregister_shouldBeNoOpForNull() {
        assertThatNoException().isThrownBy(() -> ModelTokenLimits.unregister(null));
    }

    // ========== HAS MODEL ==========

    @Test
    void hasModel_shouldReturnTrueForRegistered() {
        ModelTokenLimits.register(PREFIX + "a", 8_192);
        assertThat(ModelTokenLimits.hasModel(PREFIX + "a")).isTrue();
    }

    @Test
    void hasModel_shouldReturnFalseForUnknown() {
        assertThat(ModelTokenLimits.hasModel("never-registered-xyz")).isFalse();
    }

    @Test
    void hasModel_shouldReturnFalseForNull() {
        assertThat(ModelTokenLimits.hasModel(null)).isFalse();
    }

    // ========== GET LIMIT OR DEFAULT ==========

    @Test
    void getLimitOrDefault_shouldReturnRegisteredLimit() {
        ModelTokenLimits.register(PREFIX + "a", 8_192);

        assertThat(ModelTokenLimits.getLimitOrDefault(PREFIX + "a", 999)).isEqualTo(8_192);
    }

    @Test
    void getLimitOrDefault_shouldReturnCallerDefaultWhenUnknown() {
        assertThat(ModelTokenLimits.getLimitOrDefault("completely-unknown-xyz", 12_345))
            .isEqualTo(12_345);
    }

    @Test
    void getLimitOrDefault_shouldReturnCallerDefaultForNull() {
        assertThat(ModelTokenLimits.getLimitOrDefault(null, 6_789)).isEqualTo(6_789);
    }

    @Test
    void getLimitOrDefault_shouldNotFallBackToGlobalDefault() {
        // Caller-supplied default must win over DEFAULT_LIMIT
        int callerDefault = ModelTokenLimits.DEFAULT_LIMIT + 1;
        assertThat(ModelTokenLimits.getLimitOrDefault("unknown-xyz", callerDefault))
            .isEqualTo(callerDefault);
    }

    // ========== GET ALL MODELS ==========

    @Test
    void getAllModels_shouldContainRegisteredModel() {
        ModelTokenLimits.register(PREFIX + "a", 4_096);

        Set<String> all = ModelTokenLimits.getAllModels();
        assertThat(all).contains(PREFIX + "a");
    }

    @Test
    void getAllModels_shouldReturnUnmodifiableView() {
        Set<String> all = ModelTokenLimits.getAllModels();
        assertThatThrownBy(() -> all.add("mutate-attempt"))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
