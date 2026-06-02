package dev.agenor.examples.support.knowledge;

import dev.agenor.core.knowledge.KnowledgeDocument;
import dev.agenor.core.knowledge.KnowledgeStore;
import dev.agenor.examples.support.model.SupportIntent;
import dev.agenor.runtime.knowledge.InMemoryKnowledgeStore;

import java.util.List;
import java.util.Set;

/**
 * Sample knowledge base content for FinanceCloud personal finance app.
 */
public final class SupportKnowledgeData {

    private SupportKnowledgeData() {}

    /**
     * Returns all FAQ documents for the knowledge store.
     */
    public static List<KnowledgeDocument<SupportIntent>> getAllDocuments() {
        return List.of(
            // === ACCOUNT ===
            new KnowledgeDocument<>(
                "acc-001",
                "How to create an account",
                """
                To create a FinanceCloud account:
                1. Download the app from App Store or Google Play
                2. Tap 'Sign Up' on the welcome screen
                3. Enter your email address and create a password
                4. Verify your email with the code sent
                5. Complete your profile with name and phone number
                6. Set up a 4-digit PIN for quick access

                Your account is now ready! You can start by linking your first bank account.
                """,
                SupportIntent.ACCOUNT,
                Set.of("create", "register", "signup", "new account", "join", "start")
            ),

            new KnowledgeDocument<>(
                "acc-002",
                "How to update profile information",
                """
                To update your profile:
                1. Open the app and go to Settings (gear icon)
                2. Tap 'Profile' at the top
                3. Edit any field: name, email, phone, address
                4. Tap 'Save Changes'

                Note: Changing your email requires verification. A code will be sent to your new email.
                """,
                SupportIntent.ACCOUNT,
                Set.of("profile", "update", "edit", "change", "name", "email", "phone", "personal")
            ),

            new KnowledgeDocument<>(
                "acc-003",
                "How to close my account",
                """
                To close your FinanceCloud account:
                1. Ensure all linked accounts are disconnected
                2. Go to Settings > Account > Close Account
                3. Enter your password to confirm
                4. Select a reason for leaving (optional)
                5. Tap 'Close My Account'

                Important: This action is permanent. Your data will be deleted after 30 days.
                You can request a data export before closing.
                """,
                SupportIntent.ACCOUNT,
                Set.of("close", "delete", "remove", "cancel", "terminate", "deactivate")
            ),

            // === SECURITY ===
            new KnowledgeDocument<>(
                "sec-001",
                "How to reset my password",
                """
                To reset your password:
                1. On the login screen, tap 'Forgot Password'
                2. Enter your registered email address
                3. Check your email for the reset link (valid for 1 hour)
                4. Click the link and create a new password
                5. Password must be 8+ characters with at least one number and symbol

                If you don't receive the email, check spam folder or contact support.
                """,
                SupportIntent.SECURITY,
                Set.of("password", "reset", "forgot", "change password", "login", "cant login")
            ),

            new KnowledgeDocument<>(
                "sec-002",
                "How to enable two-factor authentication (2FA)",
                """
                To enable 2FA for extra security:
                1. Go to Settings > Security > Two-Factor Authentication
                2. Choose your method: SMS or Authenticator App
                3. For SMS: Verify your phone number
                4. For App: Scan the QR code with Google Authenticator or similar
                5. Enter the verification code to confirm setup
                6. Save your backup codes in a safe place

                With 2FA enabled, you'll need a code each time you log in from a new device.
                """,
                SupportIntent.SECURITY,
                Set.of("2fa", "two factor", "authenticator", "security", "verification", "otp")
            ),

            new KnowledgeDocument<>(
                "sec-003",
                "How to manage trusted devices",
                """
                To view and manage your trusted devices:
                1. Go to Settings > Security > Trusted Devices
                2. See all devices where you're currently logged in
                3. Tap any device to see last activity time and location
                4. Swipe left on a device and tap 'Remove' to revoke access
                5. Tap 'Log Out All Devices' to sign out everywhere except current device

                We recommend reviewing this list monthly for security.
                """,
                SupportIntent.SECURITY,
                Set.of("devices", "trusted", "logout", "sessions", "logged in", "unauthorized")
            ),

            // === TRANSACTIONS ===
            new KnowledgeDocument<>(
                "txn-001",
                "How to view transaction history",
                """
                To view your transactions:
                1. Tap the 'Activity' tab in the bottom menu
                2. See recent transactions sorted by date
                3. Use filters: date range, account, category, amount
                4. Tap any transaction for full details
                5. Search by merchant name or amount using the search bar

                Transaction history is available for the past 24 months.
                """,
                SupportIntent.TRANSACTION,
                Set.of("transactions", "history", "activity", "payments", "purchases", "view")
            ),

            new KnowledgeDocument<>(
                "txn-002",
                "How to export transactions",
                """
                To export your transaction data:
                1. Go to Activity tab
                2. Tap the export icon (top right)
                3. Select date range for export
                4. Choose format: CSV, PDF, or OFX
                5. Select destination: email, cloud storage, or download
                6. Tap 'Export'

                CSV is best for spreadsheets, PDF for records, OFX for accounting software.
                """,
                SupportIntent.TRANSACTION,
                Set.of("export", "download", "csv", "pdf", "statement", "report")
            ),

            new KnowledgeDocument<>(
                "txn-003",
                "How to dispute a transaction",
                """
                To dispute an unauthorized or incorrect transaction:
                1. Find the transaction in Activity
                2. Tap on it to open details
                3. Scroll down and tap 'Report a Problem'
                4. Select issue type: unauthorized, duplicate, wrong amount, other
                5. Add description and any supporting documents
                6. Submit the dispute

                We'll investigate within 3-5 business days and update you via email.
                For urgent fraud cases, call our hotline: 1-800-FINANCE.
                """,
                SupportIntent.TRANSACTION,
                Set.of("dispute", "fraud", "unauthorized", "wrong", "charge", "refund", "problem")
            ),

            // === BUDGET ===
            new KnowledgeDocument<>(
                "bud-001",
                "How to create a budget",
                """
                To create a new budget:
                1. Go to the 'Budget' tab
                2. Tap '+ Create Budget'
                3. Choose a category (e.g., Groceries, Entertainment)
                4. Set your monthly limit
                5. Choose notification preferences (50%, 75%, 100% alerts)
                6. Tap 'Create'

                Your budget resets on the 1st of each month. You can customize this in settings.
                """,
                SupportIntent.BUDGET,
                Set.of("budget", "create", "limit", "spending", "monthly", "category")
            ),

            new KnowledgeDocument<>(
                "bud-002",
                "How budget alerts work",
                """
                Budget alerts notify you when approaching your spending limit:
                - At 50%: Optional reminder (enable in settings)
                - At 75%: Warning notification
                - At 100%: Limit reached notification
                - Over budget: Daily summary of overspending

                To customize alerts:
                1. Go to Budget tab
                2. Tap the budget you want to modify
                3. Tap 'Alert Settings'
                4. Toggle notifications on/off for each threshold

                Alerts are sent via push notification and email.
                """,
                SupportIntent.BUDGET,
                Set.of("alert", "notification", "warning", "limit", "overspending", "notify")
            ),

            // === FAQ / GENERAL ===
            new KnowledgeDocument<>(
                "faq-001",
                "Which banks are supported",
                """
                FinanceCloud supports 12,000+ financial institutions including:
                - Major banks: Chase, Bank of America, Wells Fargo, Citi, Capital One
                - Credit unions and regional banks
                - Investment accounts: Fidelity, Schwab, Vanguard
                - Credit cards: all major issuers
                - PayPal, Venmo, and other payment services

                To check if your bank is supported:
                1. Go to Settings > Link Account
                2. Search for your institution
                3. If not found, tap 'Request Integration' to notify us
                """,
                SupportIntent.FAQ,
                Set.of("bank", "supported", "connect", "link", "institution", "chase", "integration")
            ),

            new KnowledgeDocument<>(
                "faq-002",
                "Is my data secure",
                """
                Yes, FinanceCloud uses bank-level security:
                - 256-bit AES encryption for all data
                - Read-only access to your accounts (we cannot move money)
                - SOC 2 Type II certified
                - No storage of your bank passwords
                - Biometric authentication support
                - Automatic session timeout

                We use Plaid, a trusted service used by major financial apps,
                to securely connect to your bank.
                """,
                SupportIntent.FAQ,
                Set.of("security", "safe", "data", "privacy", "encryption", "secure", "plaid")
            ),

            new KnowledgeDocument<>(
                "faq-003",
                "Subscription plans and pricing",
                """
                FinanceCloud offers:

                FREE Plan:
                - Link up to 2 accounts
                - Basic budgeting
                - 3 months transaction history

                PREMIUM Plan ($4.99/month or $49.99/year):
                - Unlimited accounts
                - Advanced budgets and goals
                - 24 months history
                - CSV/PDF exports
                - Priority support

                FAMILY Plan ($9.99/month):
                - Up to 5 family members
                - All Premium features
                - Shared budgets and goals

                Upgrade anytime in Settings > Subscription.
                """,
                SupportIntent.FAQ,
                Set.of("price", "cost", "subscription", "premium", "free", "plan", "upgrade", "family")
            )
        );
    }

    /**
     * Initializes a KnowledgeStore with all sample documents.
     * Uses InMemoryKnowledgeStore (simple keyword matching).
     */
    public static KnowledgeStore<SupportIntent> createPopulatedStore() {
        KnowledgeStore<SupportIntent> store = new InMemoryKnowledgeStore<SupportIntent>();
        getAllDocuments().forEach(store::add);
        return store;
    }

    /**
     * Initializes a SemanticKnowledgeStore with TF-IDF ranking.
     * Provides better relevance scoring than simple keyword matching.
     */
    public static SemanticKnowledgeStore createSemanticStore() {
        SemanticKnowledgeStore store = new SemanticKnowledgeStore();
        getAllDocuments().forEach(store::add);
        store.buildIndex();
        return store;
    }

    /**
     * Initializes a HybridKnowledgeStore with TF-IDF + embeddings.
     * Requires embedding model for vector search.
     * Falls back to TF-IDF only if embeddings unavailable.
     */
    public static HybridKnowledgeStore createHybridStore(EmbeddingConfig embeddingConfig) {
        HybridKnowledgeStore store;

        if (embeddingConfig != null && embeddingConfig.isEnabled()) {
            var embeddingModel = embeddingConfig.createModel();
            if (embeddingModel != null) {
                store = new HybridKnowledgeStore(
                    embeddingModel,
                    embeddingConfig.getDimensions(),
                    0.4,  // TF-IDF weight
                    0.6   // Embeddings weight
                );
            } else {
                store = new HybridKnowledgeStore(); // TF-IDF only fallback
            }
        } else {
            store = new HybridKnowledgeStore(); // TF-IDF only
        }

        getAllDocuments().forEach(store::add);
        store.buildIndex();
        return store;
    }
}
