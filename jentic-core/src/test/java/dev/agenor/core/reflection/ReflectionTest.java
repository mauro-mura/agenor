package dev.agenor.core.reflection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReflectionTest {

    // =========================================================================
    // CritiqueResult
    // =========================================================================

    @Nested
    @DisplayName("CritiqueResult")
    class CritiqueResultTests {

        @Test
        @DisplayName("canonical constructor stores all fields")
        void constructor_storesFields() {
            CritiqueResult r = new CritiqueResult("needs work", true, 0.6);
            assertThat(r.feedback()).isEqualTo("needs work");
            assertThat(r.shouldRevise()).isTrue();
            assertThat(r.score()).isEqualTo(0.6);
        }

        @Test
        @DisplayName("score below 0.0 → IllegalArgumentException")
        void score_belowZero_throws() {
            assertThatThrownBy(() -> new CritiqueResult("fb", false, -0.1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("score");
        }

        @Test
        @DisplayName("score above 1.0 → IllegalArgumentException")
        void score_aboveOne_throws() {
            assertThatThrownBy(() -> new CritiqueResult("fb", false, 1.1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("score");
        }

        @Test
        @DisplayName("null feedback → IllegalArgumentException")
        void nullFeedback_throws() {
            assertThatThrownBy(() -> new CritiqueResult(null, false, 0.5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("feedback");
        }

        @Test
        @DisplayName("accepted() → shouldRevise=false, empty feedback")
        void accepted_factory() {
            CritiqueResult r = CritiqueResult.accepted(0.9);
            assertThat(r.shouldRevise()).isFalse();
            assertThat(r.feedback()).isEmpty();
            assertThat(r.score()).isEqualTo(0.9);
        }

        @Test
        @DisplayName("revise() → shouldRevise=true, feedback set")
        void revise_factory() {
            CritiqueResult r = CritiqueResult.revise("add examples", 0.5);
            assertThat(r.shouldRevise()).isTrue();
            assertThat(r.feedback()).isEqualTo("add examples");
            assertThat(r.score()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("boundary score 0.0 is valid")
        void score_zero_valid() {
            CritiqueResult r = new CritiqueResult("", false, 0.0);
            assertThat(r.score()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("boundary score 1.0 is valid")
        void score_one_valid() {
            CritiqueResult r = new CritiqueResult("", false, 1.0);
            assertThat(r.score()).isEqualTo(1.0);
        }
    }

    // =========================================================================
    // ReflectionConfig
    // =========================================================================

    @Nested
    @DisplayName("ReflectionConfig")
    class ReflectionConfigTests {

        @Test
        @DisplayName("defaults() returns maxIterations=2, threshold=0.8, null prompt")
        void defaults_values() {
            ReflectionConfig c = ReflectionConfig.defaults();
            assertThat(c.maxIterations()).isEqualTo(2);
            assertThat(c.scoreThreshold()).isEqualTo(0.8);
            assertThat(c.critiquePrompt()).isNull();
        }

        @Test
        @DisplayName("canonical constructor stores all fields")
        void constructor_storesFields() {
            ReflectionConfig c = new ReflectionConfig(3, 0.9, "custom prompt");
            assertThat(c.maxIterations()).isEqualTo(3);
            assertThat(c.scoreThreshold()).isEqualTo(0.9);
            assertThat(c.critiquePrompt()).isEqualTo("custom prompt");
        }

        @Test
        @DisplayName("maxIterations < 1 → IllegalArgumentException")
        void maxIterations_zero_throws() {
            assertThatThrownBy(() -> new ReflectionConfig(0, 0.8, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxIterations");
        }

        @Test
        @DisplayName("scoreThreshold below 0.0 → IllegalArgumentException")
        void threshold_belowZero_throws() {
            assertThatThrownBy(() -> new ReflectionConfig(2, -0.1, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("scoreThreshold");
        }

        @Test
        @DisplayName("scoreThreshold above 1.0 → IllegalArgumentException")
        void threshold_aboveOne_throws() {
            assertThatThrownBy(() -> new ReflectionConfig(2, 1.1, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("scoreThreshold");
        }

        @Test
        @DisplayName("hasCustomPrompt() true when prompt is set")
        void hasCustomPrompt_true() {
            assertThat(new ReflectionConfig(1, 0.8, "my prompt").hasCustomPrompt()).isTrue();
        }

        @Test
        @DisplayName("hasCustomPrompt() false when prompt is null")
        void hasCustomPrompt_null() {
            assertThat(new ReflectionConfig(1, 0.8, null).hasCustomPrompt()).isFalse();
        }

        @Test
        @DisplayName("hasCustomPrompt() false when prompt is blank")
        void hasCustomPrompt_blank() {
            assertThat(new ReflectionConfig(1, 0.8, "   ").hasCustomPrompt()).isFalse();
        }

        @Test
        @DisplayName("boundary maxIterations=1 is valid")
        void maxIterations_one_valid() {
            ReflectionConfig c = new ReflectionConfig(1, 0.8, null);
            assertThat(c.maxIterations()).isEqualTo(1);
        }
    }
}
