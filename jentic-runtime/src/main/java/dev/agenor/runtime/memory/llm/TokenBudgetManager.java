package dev.agenor.runtime.memory.llm;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages token budget allocation and tracking.
 *
 * <p>This class helps manage token budgets for LLM operations by:
 * <ul>
 *   <li>Tracking total budget and used tokens</li>
 *   <li>Checking if allocation is possible</li>
 *   <li>Allocating and releasing tokens</li>
 *   <li>Calculating remaining budget</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> This class uses atomic operations and is thread-safe.
 *
 * <p><b>Example Usage:</b>
 * <pre>{@code
 * // Create budget manager with 2000 token budget
 * TokenBudgetManager budget = new TokenBudgetManager(2000);
 *
 * // Check if can allocate tokens
 * if (budget.canAllocate(500)) {
 *     budget.allocate(500);
 *     // Use tokens...
 *     budget.release(500);  // Release when done
 * }
 *
 * // Check remaining
 * int remaining = budget.getRemaining();  // 2000
 * }</pre>
 *
 * <p><b>Use Cases:</b>
 * <ul>
 *   <li>Managing context window budget across multiple memory retrievals</li>
 *   <li>Ensuring total message tokens don't exceed model limits</li>
 *   <li>Tracking token usage for cost estimation</li>
 * </ul>
 *
 * @since 0.7.0
 */
public class TokenBudgetManager {

    /**
     * Total token budget.
     */
    private final int totalBudget;

    /**
     * Currently allocated tokens (atomic for thread safety).
     */
    private final AtomicInteger used;

    /**
     * Create a token budget manager with the specified budget.
     *
     * @param totalBudget the total token budget
     * @throws IllegalArgumentException if totalBudget {@literal <=} 0
     */
    public TokenBudgetManager(int totalBudget) {
        if (totalBudget <= 0) {
            throw new IllegalArgumentException("Total budget must be positive");
        }
        this.totalBudget = totalBudget;
        this.used = new AtomicInteger(0);
    }

    /**
     * Get the total budget.
     *
     * @return total token budget
     */
    public int getTotalBudget() {
        return totalBudget;
    }

    /**
     * Get the currently used tokens.
     *
     * @return number of allocated tokens
     */
    public int getUsed() {
        return used.get();
    }

    /**
     * Get the remaining budget.
     *
     * @return number of available tokens
     */
    public int getRemaining() {
        return Math.max(0, totalBudget - used.get());
    }

    /**
     * Get the usage percentage.
     *
     * @return percentage of budget used (0.0 to 100.0)
     */
    public double getUsagePercentage() {
        return (used.get() * 100.0) / totalBudget;
    }

    /**
     * Check if budget is exhausted.
     *
     * @return true if no tokens remaining
     */
    public boolean isExhausted() {
        return used.get() >= totalBudget;
    }

    /**
     * Check if the specified number of tokens can be allocated.
     *
     * <p>This checks if there's enough remaining budget without
     * actually allocating tokens.
     *
     * @param tokens the number of tokens to check
     * @return true if allocation would succeed
     * @throws IllegalArgumentException if tokens {@literal <} 0
     */
    public boolean canAllocate(int tokens) {
        if (tokens < 0) {
            throw new IllegalArgumentException("Tokens cannot be negative");
        }
        return used.get() + tokens <= totalBudget;
    }

    /**
     * Allocate tokens from the budget.
     *
     * <p>This atomically increments the used count if the allocation
     * would not exceed the budget.
     *
     * @param tokens the number of tokens to allocate
     * @return true if allocation succeeded, false if budget insufficient
     * @throws IllegalArgumentException if tokens {@literal <} 0
     */
    public boolean allocate(int tokens) {
        if (tokens < 0) {
            throw new IllegalArgumentException("Tokens cannot be negative");
        }

        // Atomic compare-and-set loop
        while (true) {
            int currentUsed = used.get();
            int newUsed = currentUsed + tokens;

            // Check if would exceed budget
            if (newUsed > totalBudget) {
                return false;  // Allocation failed
            }

            // Try to update atomically
            if (used.compareAndSet(currentUsed, newUsed)) {
                return true;  // Allocation succeeded
            }

            // CAS failed, retry
        }
    }

    /**
     * Allocate tokens or throw exception if insufficient budget.
     *
     * @param tokens the number of tokens to allocate
     * @throws IllegalArgumentException if tokens {@literal <} 0
     * @throws InsufficientBudgetException if budget insufficient
     */
    public void allocateOrThrow(int tokens) {
        if (!allocate(tokens)) {
            throw new InsufficientBudgetException(
                "Cannot allocate " + tokens + " tokens. " +
                "Used: " + used.get() + "/" + totalBudget
            );
        }
    }

    /**
     * Release tokens back to the budget.
     *
     * <p>This atomically decrements the used count. Released tokens
     * become available for re-allocation.
     *
     * @param tokens the number of tokens to release
     * @throws IllegalArgumentException if tokens {@literal <} 0
     */
    public void release(int tokens) {
        if (tokens < 0) {
            throw new IllegalArgumentException("Tokens cannot be negative");
        }

        // Atomic decrement
        used.updateAndGet(current -> Math.max(0, current - tokens));
    }

    /**
     * Reset the budget to zero usage.
     *
     * <p>This clears all allocations and makes the entire budget
     * available again.
     */
    public void reset() {
        used.set(0);
    }

    /**
     * Create a snapshot of the current budget state.
     *
     * @return budget snapshot
     */
    public BudgetSnapshot snapshot() {
        int currentUsed = used.get();
        return new BudgetSnapshot(
            totalBudget,
            currentUsed,
            totalBudget - currentUsed
        );
    }

    @Override
    public String toString() {
        return String.format(
            "TokenBudgetManager{total=%d, used=%d, remaining=%d (%.1f%%)}",
            totalBudget,
            used.get(),
            getRemaining(),
            getUsagePercentage()
        );
    }

    /**
     * Immutable snapshot of budget state.
     */
    public record BudgetSnapshot(
        int total,
        int used,
        int remaining
    ) {
        public double usagePercentage() {
            return (used * 100.0) / total;
        }

        public boolean isExhausted() {
            return remaining <= 0;
        }
    }

    /**
     * Exception thrown when insufficient budget for allocation.
     */
    public static class InsufficientBudgetException extends RuntimeException {
        public InsufficientBudgetException(String message) {
            super(message);
        }
    }
}
