package dev.agenor.runtime.memory.llm;

import dev.agenor.runtime.memory.llm.TokenBudgetManager.BudgetSnapshot;
import dev.agenor.runtime.memory.llm.TokenBudgetManager.InsufficientBudgetException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for TokenBudgetManager.
 */
class TokenBudgetManagerTest {

    // ========== CONSTRUCTION TESTS ==========

    @Test
    void constructor_shouldAcceptPositiveBudget() {
        // When
        TokenBudgetManager budget = new TokenBudgetManager(1000);

        // Then
        assertThat(budget.getTotalBudget()).isEqualTo(1000);
        assertThat(budget.getUsed()).isZero();
        assertThat(budget.getRemaining()).isEqualTo(1000);
    }

    @Test
    void constructor_shouldThrowForZeroBudget() {
        // When/Then
        assertThatThrownBy(() -> new TokenBudgetManager(0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Total budget must be positive");
    }

    @Test
    void constructor_shouldThrowForNegativeBudget() {
        // When/Then
        assertThatThrownBy(() -> new TokenBudgetManager(-100))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Total budget must be positive");
    }

    // ========== BASIC ALLOCATION TESTS ==========

    @Test
    void allocate_shouldDecreaseRemaining() {
        // Given
        TokenBudgetManager budget = new TokenBudgetManager(1000);

        // When
        boolean success = budget.allocate(300);

        // Then
        assertThat(success).isTrue();
        assertThat(budget.getUsed()).isEqualTo(300);
        assertThat(budget.getRemaining()).isEqualTo(700);
    }

    @Test
    void allocate_shouldHandleMultipleAllocations() {
        // Given
        TokenBudgetManager budget = new TokenBudgetManager(1000);

        // When
        budget.allocate(200);
        budget.allocate(300);
        budget.allocate(100);

        // Then
        assertThat(budget.getUsed()).isEqualTo(600);
        assertThat(budget.getRemaining()).isEqualTo(400);
    }

    @Test
    void allocate_shouldReturnFalseWhenBudgetInsufficient() {
        // Given
        TokenBudgetManager budget = new TokenBudgetManager(1000);
        budget.allocate(800);

        // When - try to allocate more than remaining
        boolean success = budget.allocate(300);

        // Then
        assertThat(success).isFalse();
        assertThat(budget.getUsed()).isEqualTo(800);  // Unchanged
    }

    @Test
    void allocate_shouldAllowExactBudgetAllocation() {
        // Given
        TokenBudgetManager budget = new TokenBudgetManager(1000);

        // When - allocate exactly all budget
        boolean success = budget.allocate(1000);

        // Then
        assertThat(success).isTrue();
        assertThat(budget.getUsed()).isEqualTo(1000);
        assertThat(budget.getRemaining()).isZero();
        assertThat(budget.isExhausted()).isTrue();
    }

    @Test
    void allocate_shouldThrowForNegativeTokens() {
        // Given
        TokenBudgetManager budget = new TokenBudgetManager(1000);

        // When/Then
        assertThatThrownBy(() -> budget.allocate(-10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Tokens cannot be negative");
    }

    @Test
    void allocate_shouldAllowZeroTokens() {
        // Given
        TokenBudgetManager budget = new TokenBudgetManager(1000);

        // When
        boolean success = budget.allocate(0);

        // Then
        assertThat(success).isTrue();
        assertThat(budget.getUsed()).isZero();
    }

    // ========== CAN ALLOCATE TESTS ==========

    @Test
    void canAllocate_shouldReturnTrueWhenSufficientBudget() {
        // Given
        TokenBudgetManager budget = new TokenBudgetManager(1000);
        budget.allocate(300);

        // When
        boolean can = budget.canAllocate(500);

        // Then
        assertThat(can).isTrue();
        assertThat(budget.getUsed()).isEqualTo(300);  // Unchanged
    }

    @Test
    void canAllocate_shouldReturnFalseWhenInsufficientBudget() {
        // Given
        TokenBudgetManager budget = new TokenBudgetManager(1000);
        budget.allocate(800);

        // When
        boolean can = budget.canAllocate(300);

        // Then
        assertThat(can).isFalse();
    }

    @Test
    void canAllocate_shouldNotModifyBudget() {
        // Given
        TokenBudgetManager budget = new TokenBudgetManager(1000);
        budget.allocate(500);

        // When
        budget.canAllocate(200);
        budget.canAllocate(800);

        // Then - should be unchanged
        assertThat(budget.getUsed()).isEqualTo(500);
    }

    // ========== ALLOCATE OR THROW TESTS ==========

    @Test
    void allocateOrThrow_shouldSucceedWhenSufficientBudget() {
        // Given
        TokenBudgetManager budget = new TokenBudgetManager(1000);

        // When/Then - should not throw
        assertThatCode(() -> budget.allocateOrThrow(500))
            .doesNotThrowAnyException();

        assertThat(budget.getUsed()).isEqualTo(500);
    }

    @Test
    void allocateOrThrow_shouldThrowWhenInsufficientBudget() {
        // Given
        TokenBudgetManager budget = new TokenBudgetManager(1000);
        budget.allocate(800);

        // When/Then
        assertThatThrownBy(() -> budget.allocateOrThrow(300))
            .isInstanceOf(InsufficientBudgetException.class)
            .hasMessageContaining("Cannot allocate 300 tokens");
    }

    // ========== RELEASE TESTS ==========

    @Test
    void release_shouldIncreaseRemaining() {
        // Given
        TokenBudgetManager budget = new TokenBudgetManager(1000);
        budget.allocate(600);

        // When
        budget.release(200);

        // Then
        assertThat(budget.getUsed()).isEqualTo(400);
        assertThat(budget.getRemaining()).isEqualTo(600);
    }

    @Test
    void release_shouldNotGoNegative() {
        // Given
        TokenBudgetManager budget = new TokenBudgetManager(1000);
        budget.allocate(300);

        // When - release more than allocated
        budget.release(500);

        // Then - should clamp to zero
        assertThat(budget.getUsed()).isZero();
        assertThat(budget.getRemaining()).isEqualTo(1000);
    }

    @Test
    void release_shouldThrowForNegativeTokens() {
        // Given
        TokenBudgetManager budget = new TokenBudgetManager(1000);

        // When/Then
        assertThatThrownBy(() -> budget.release(-10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Tokens cannot be negative");
    }

    @Test
    void release_shouldAllowZeroTokens() {
        // Given
        TokenBudgetManager budget = new TokenBudgetManager(1000);
        budget.allocate(300);

        // When
        budget.release(0);

        // Then - should be unchanged
        assertThat(budget.getUsed()).isEqualTo(300);
    }

    // ========== RESET TESTS ==========

    @Test
    void reset_shouldClearAllAllocations() {
        // Given
        TokenBudgetManager budget = new TokenBudgetManager(1000);
        budget.allocate(600);

        // When
        budget.reset();

        // Then
        assertThat(budget.getUsed()).isZero();
        assertThat(budget.getRemaining()).isEqualTo(1000);
    }

    @Test
    void reset_shouldAllowReallocation() {
        // Given
        TokenBudgetManager budget = new TokenBudgetManager(1000);
        budget.allocate(1000);  // Exhaust budget
        budget.reset();

        // When
        boolean success = budget.allocate(500);

        // Then
        assertThat(success).isTrue();
    }

    // ========== USAGE PERCENTAGE TESTS ==========

    @Test
    void getUsagePercentage_shouldReturnZeroInitially() {
        // Given
        TokenBudgetManager budget = new TokenBudgetManager(1000);

        // Then
        assertThat(budget.getUsagePercentage()).isZero();
    }

    @Test
    void getUsagePercentage_shouldCalculateCorrectly() {
        // Given
        TokenBudgetManager budget = new TokenBudgetManager(1000);
        budget.allocate(250);

        // Then
        assertThat(budget.getUsagePercentage()).isEqualTo(25.0);
    }

    @Test
    void getUsagePercentage_shouldReturn100WhenExhausted() {
        // Given
        TokenBudgetManager budget = new TokenBudgetManager(1000);
        budget.allocate(1000);

        // Then
        assertThat(budget.getUsagePercentage()).isEqualTo(100.0);
    }

    // ========== EXHAUSTION TESTS ==========

    @Test
    void isExhausted_shouldReturnFalseInitially() {
        // Given
        TokenBudgetManager budget = new TokenBudgetManager(1000);

        // Then
        assertThat(budget.isExhausted()).isFalse();
    }

    @Test
    void isExhausted_shouldReturnTrueWhenBudgetFull() {
        // Given
        TokenBudgetManager budget = new TokenBudgetManager(1000);
        budget.allocate(1000);

        // Then
        assertThat(budget.isExhausted()).isTrue();
    }

    @Test
    void isExhausted_shouldReturnFalseAfterRelease() {
        // Given
        TokenBudgetManager budget = new TokenBudgetManager(1000);
        budget.allocate(1000);
        budget.release(100);

        // Then
        assertThat(budget.isExhausted()).isFalse();
    }

    // ========== SNAPSHOT TESTS ==========

    @Test
    void snapshot_shouldCaptureCurrentState() {
        // Given
        TokenBudgetManager budget = new TokenBudgetManager(1000);
        budget.allocate(600);

        // When
        BudgetSnapshot snapshot = budget.snapshot();

        // Then
        assertThat(snapshot.total()).isEqualTo(1000);
        assertThat(snapshot.used()).isEqualTo(600);
        assertThat(snapshot.remaining()).isEqualTo(400);
        assertThat(snapshot.usagePercentage()).isEqualTo(60.0);
        assertThat(snapshot.isExhausted()).isFalse();
    }

    @Test
    void snapshot_shouldBeImmutable() {
        // Given
        TokenBudgetManager budget = new TokenBudgetManager(1000);
        budget.allocate(500);
        BudgetSnapshot snapshot = budget.snapshot();

        // When - modify budget
        budget.allocate(300);

        // Then - snapshot unchanged
        assertThat(snapshot.used()).isEqualTo(500);
        assertThat(budget.getUsed()).isEqualTo(800);
    }

    // ========== THREAD SAFETY TESTS ==========

    @Test
    void allocate_shouldBeThreadSafe() throws InterruptedException {
        // Given
        TokenBudgetManager budget = new TokenBudgetManager(10000);
        int threadCount = 200;
        int tokensPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        // When - allocate concurrently
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                if (budget.allocate(tokensPerThread)) {
                    successCount.incrementAndGet();
                }
                latch.countDown();
            });
        }

        latch.await();
        executor.shutdown();

        // Then - should allocate correctly
        int expectedAllocations = 10000 / tokensPerThread;  // 200
        assertThat(successCount.get()).isEqualTo(expectedAllocations);
        assertThat(budget.getUsed()).isEqualTo(expectedAllocations * tokensPerThread);
    }

    @Test
    void release_shouldBeThreadSafe() throws InterruptedException {
        TokenBudgetManager budget = new TokenBudgetManager(10000);
        budget.allocate(5000);

        int threadCount = 50;
        int tokensPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                budget.release(tokensPerThread);
                latch.countDown();
            });
        }

        latch.await();
        executor.shutdown();

        // release() clamps at 0 — expectedUsed reflects the actual floor
        int expectedUsed = Math.max(0, 5000 - (threadCount * tokensPerThread));
        assertThat(budget.getUsed()).isEqualTo(expectedUsed);
    }

    // ========== TO STRING TESTS ==========

    @Test
    void toString_shouldProvideUsefulInfo() {
        // Given
        TokenBudgetManager budget = new TokenBudgetManager(2000);
        budget.allocate(500);

        // When
        String str = budget.toString();

        // Then
        assertThat(str).contains("2000");  // total
        assertThat(str).contains("500");   // used
        assertThat(str).contains("1500");  // remaining
    }
}
