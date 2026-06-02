package dev.agenor.examples.support.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mock service simulating FinanceCloud backend data.
 * Provides user accounts, transactions, and security info.
 */
public class MockUserDataService {

    private final Map<String, UserProfile> users = new ConcurrentHashMap<>();
    private final Map<String, List<Transaction>> transactions = new ConcurrentHashMap<>();
    private final Map<String, SecuritySettings> securitySettings = new ConcurrentHashMap<>();
    private final Map<String, List<Budget>> budgets = new ConcurrentHashMap<>();

    public MockUserDataService() {
        initializeSampleData();
    }

    // ========== USER PROFILE ==========

    public Optional<UserProfile> getUser(String userId) {
        return Optional.ofNullable(users.get(userId));
    }

    public record UserProfile(
        String userId,
        String name,
        String email,
        String phone,
        String plan,
        BigDecimal balance,
        LocalDate memberSince,
        List<LinkedAccount> linkedAccounts
    ) {}

    public record LinkedAccount(
        String accountId,
        String bankName,
        String accountType,
        String lastFour,
        BigDecimal balance,
        LocalDateTime lastSync
    ) {}

    public List<LinkedAccount> getLinkedAccounts(String userId) {
        return getUser(userId)
            .map(UserProfile::linkedAccounts)
            .orElse(List.of());
    }

    // ========== TRANSACTIONS ==========

    public List<Transaction> getTransactions(String userId, int limit) {
        return transactions.getOrDefault(userId, List.of()).stream()
            .limit(limit)
            .toList();
    }

    public List<Transaction> getRecentTransactions(String userId, int limit) {
        return getTransactions(userId, limit);
    }

    public List<Transaction> getTransactionsByCategory(String userId, String category) {
        return transactions.getOrDefault(userId, List.of()).stream()
            .filter(t -> t.category().equalsIgnoreCase(category))
            .toList();
    }

    public Optional<Transaction> getTransaction(String userId, String transactionId) {
        return transactions.getOrDefault(userId, List.of()).stream()
            .filter(t -> t.id().equals(transactionId))
            .findFirst();
    }

    public record Transaction(
        String id,
        LocalDateTime date,
        String merchant,
        String category,
        BigDecimal amount,
        String status,
        String accountId
    ) {
        public boolean isDebit() {
            return amount.compareTo(BigDecimal.ZERO) < 0;
        }
    }

    // ========== SECURITY ==========

    public Optional<SecuritySettings> getSecuritySettingsOpt(String userId) {
        return Optional.ofNullable(securitySettings.get(userId));
    }

    public SecuritySettings getSecuritySettings(String userId) {
        return securitySettings.getOrDefault(userId, new SecuritySettings(
            userId, false, "none", List.of(), LocalDateTime.now().minusDays(30), 0, false
        ));
    }

    public List<TrustedDevice> getTrustedDevices(String userId) {
        SecuritySettings settings = securitySettings.get(userId);
        return settings != null ? settings.trustedDevices() : List.of();
    }

    public boolean initiate2FASetup(String userId, String method) {
        // Simulate 2FA setup initiation
        return true;
    }

    public boolean initiatePasswordReset(String userId) {
        // Simulate password reset email
        return true;
    }

    public record SecuritySettings(
        String userId,
        boolean twoFactorEnabled,
        String twoFactorMethod,
        List<TrustedDevice> trustedDevices,
        LocalDateTime lastPasswordChange,
        int failedLoginAttempts,
        boolean accountLocked
    ) {}

    public record TrustedDevice(
        String deviceId,
        String deviceName,
        String deviceType,
        LocalDateTime lastUsed,
        String location
    ) {}

    // ========== BUDGETS ==========

    public List<Budget> getBudgets(String userId) {
        return budgets.getOrDefault(userId, List.of());
    }

    public record Budget(
        String id,
        String category,
        BigDecimal limit,
        BigDecimal spent,
        LocalDate periodStart,
        LocalDate periodEnd,
        boolean alertEnabled
    ) {
        public BigDecimal remaining() {
            return limit.subtract(spent);
        }

        public int percentUsed() {
            if (limit.compareTo(BigDecimal.ZERO) == 0) return 0;
            return spent.multiply(BigDecimal.valueOf(100))
                .divide(limit, 0, java.math.RoundingMode.HALF_UP)
                .intValue();
        }
    }

    // ========== SAMPLE DATA ==========

    private void initializeSampleData() {
        String userId = "user-console";

        // User profile
        users.put(userId, new UserProfile(
            userId,
            "Demo User",
            "demo@example.com",
            "+1-555-0123",
            "Premium",
            new BigDecimal("2011.11"),  // Total balance across accounts
            LocalDate.of(2023, 6, 15),
            List.of(
                new LinkedAccount("acc-1", "Chase", "Checking", "4521",
                    new BigDecimal("3245.67"), LocalDateTime.now().minusHours(2)),
                new LinkedAccount("acc-2", "Capital One", "Credit Card", "9876",
                    new BigDecimal("-1234.56"), LocalDateTime.now().minusHours(1))
            )
        ));

        // Transactions
        transactions.put(userId, List.of(
            new Transaction("txn-001", LocalDateTime.now().minusDays(1),
                "Amazon", "Shopping", new BigDecimal("-89.99"), "completed", "acc-2"),
            new Transaction("txn-002", LocalDateTime.now().minusDays(2),
                "Starbucks", "Food & Drink", new BigDecimal("-5.75"), "completed", "acc-2"),
            new Transaction("txn-003", LocalDateTime.now().minusDays(3),
                "Salary Deposit", "Income", new BigDecimal("3500.00"), "completed", "acc-1"),
            new Transaction("txn-004", LocalDateTime.now().minusDays(4),
                "Netflix", "Entertainment", new BigDecimal("-15.99"), "completed", "acc-2"),
            new Transaction("txn-005", LocalDateTime.now().minusDays(5),
                "Whole Foods", "Groceries", new BigDecimal("-127.34"), "completed", "acc-2"),
            new Transaction("txn-006", LocalDateTime.now().minusDays(6),
                "Shell Gas Station", "Transportation", new BigDecimal("-45.00"), "completed", "acc-2"),
            new Transaction("txn-007", LocalDateTime.now().minusDays(7),
                "Suspicious Charge", "Unknown", new BigDecimal("-299.99"), "pending", "acc-2")
        ));

        // Security settings
        securitySettings.put(userId, new SecuritySettings(
            userId,
            true,
            "authenticator",
            List.of(
                new TrustedDevice("dev-1", "iPhone 15", "mobile",
                    LocalDateTime.now().minusMinutes(30), "New York, NY"),
                new TrustedDevice("dev-2", "MacBook Pro", "desktop",
                    LocalDateTime.now().minusDays(1), "New York, NY"),
                new TrustedDevice("dev-3", "Unknown Device", "desktop",
                    LocalDateTime.now().minusDays(10), "Unknown Location")
            ),
            LocalDateTime.now().minusDays(45),
            0,
            false  // accountLocked
        ));

        // Budgets
        budgets.put(userId, List.of(
            new Budget("bud-1", "Groceries", new BigDecimal("500"),
                new BigDecimal("327.34"), LocalDate.now().withDayOfMonth(1),
                LocalDate.now().withDayOfMonth(1).plusMonths(1).minusDays(1), true),
            new Budget("bud-2", "Entertainment", new BigDecimal("100"),
                new BigDecimal("95.99"), LocalDate.now().withDayOfMonth(1),
                LocalDate.now().withDayOfMonth(1).plusMonths(1).minusDays(1), true),
            new Budget("bud-3", "Transportation", new BigDecimal("200"),
                new BigDecimal("45.00"), LocalDate.now().withDayOfMonth(1),
                LocalDate.now().withDayOfMonth(1).plusMonths(1).minusDays(1), false)
        ));

        // Also add demo-user for demo mode
        users.put("demo-user", users.get(userId));
        transactions.put("demo-user", transactions.get(userId));
        securitySettings.put("demo-user", securitySettings.get(userId));
        budgets.put("demo-user", budgets.get(userId));
    }
}
