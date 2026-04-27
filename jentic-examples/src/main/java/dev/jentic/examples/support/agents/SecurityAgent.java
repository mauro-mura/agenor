package dev.jentic.examples.support.agents;

import dev.jentic.core.Message;
import dev.jentic.core.MessageHandler;
import dev.jentic.core.annotations.JenticAgent;
import dev.jentic.core.dialogue.DialogueMessage;
import dev.jentic.core.dialogue.Performative;
import dev.jentic.core.dialogue.DialogueHandler;
import dev.jentic.examples.support.agents.CollaborativeRouterAgent.AgentConsultation;
import dev.jentic.examples.support.agents.CollaborativeRouterAgent.AgentContribution;
import dev.jentic.examples.support.model.SupportIntent;
import dev.jentic.examples.support.model.SupportQuery;
import dev.jentic.examples.support.model.SupportResponse;
import dev.jentic.examples.support.service.MockUserDataService;
import dev.jentic.examples.support.service.MockUserDataService.SecuritySettings;
import dev.jentic.examples.support.service.MockUserDataService.TrustedDevice;
import dev.jentic.runtime.agent.BaseAgent;
import dev.jentic.runtime.dialogue.DialogueCapability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles security-related queries: password reset, 2FA, device management.
 * Supports collaborative reasoning via ConsultableAgent interface.
 */
@JenticAgent(
    value = "security-agent",
    type = "handler",
    capabilities = {"security", "authentication", "2fa", "consultable"}
)
public class SecurityAgent extends BaseAgent implements ConsultableAgent {
    
    private static final Logger log = LoggerFactory.getLogger(SecurityAgent.class);
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("MMM d, h:mm a");
    
    private final MockUserDataService dataService;
    private final DialogueCapability dialogue;
    
    public SecurityAgent(MockUserDataService dataService) {
        super("security-agent", "Security Handler");
        this.dataService = dataService;
        this.dialogue = new DialogueCapability(this);
    }
    
    @Override
    protected void onStart() {
        dialogue.initialize(getMessageDispatcher());
        getMessageDispatcher().subscribeTopic("support.security", MessageHandler.sync(this::handleSecurityQuery));
        log.info("Security Agent started with dialogue support");
    }

    @Override
    protected void onStop() {
        dialogue.shutdown();
        log.info("Security Agent stopped");
    }
    
    // ========== CONSULTABLE AGENT IMPLEMENTATION ==========
    
    @Override
    public List<SupportIntent> getExpertise() {
        return List.of(SupportIntent.SECURITY, SupportIntent.PASSWORD_RESET);
    }
    
    @DialogueHandler(performatives = {Performative.QUERY})
    public void handleConsultation(DialogueMessage msg) {
        if (msg.content() instanceof AgentConsultation consultation) {
            log.debug("Security consultation: {}", consultation.queryText());
            AgentContribution contribution = consult(consultation);
            dialogue.reply(msg, Performative.INFORM, contribution);
        }
    }
    
    @Override
    public AgentContribution consult(AgentConsultation consultation) {
        String queryText = consultation.queryText().toLowerCase();
        String userId = "demo-user";
        
        double confidence = calculateConfidence(queryText,
            "password", "security", "2fa", "login", "device", "suspicious", "locked", "hack");
        
        if (confidence < 0.3) {
            return cannotContribute("Query not related to security");
        }
        
        List<String> insights = new ArrayList<>();
        StringBuilder response = new StringBuilder();
        
        // Get security settings
        SecuritySettings settings = dataService.getSecuritySettings(userId);
        List<TrustedDevice> devices = dataService.getTrustedDevices(userId);
        
        // Password related
        if (containsAny(queryText, "password", "reset", "forgot")) {
            response.append("Password reset available via email verification. ");
            long daysSinceChange = ChronoUnit.DAYS.between(settings.lastPasswordChange(), LocalDateTime.now());
            insights.add("LAST_PASSWORD_CHANGE: " + daysSinceChange + " days ago");
            if (daysSinceChange > 90) {
                insights.add("ACTION: Recommend password update");
            }
        }
        
        // 2FA status
        if (containsAny(queryText, "2fa", "two factor", "authenticator", "secure")) {
            if (response.length() > 0) response.append("\n");
            response.append("2FA is ").append(settings.twoFactorEnabled() ? "ENABLED ✓" : "DISABLED");
            insights.add("2FA_STATUS: " + (settings.twoFactorEnabled() ? "enabled" : "disabled"));
            if (!settings.twoFactorEnabled()) {
                insights.add("ACTION: Recommend enabling 2FA");
            }
        }
        
        // Device/login activity
        if (containsAny(queryText, "device", "login", "session", "suspicious", "activity")) {
            if (response.length() > 0) response.append("\n");
            response.append("Trusted devices: ").append(devices.size());
            insights.add("TRUSTED_DEVICES: " + devices.size());
            
            // Check for recent activity
            if (!devices.isEmpty()) {
                TrustedDevice mostRecent = devices.get(0);
                long hoursSinceLogin = ChronoUnit.HOURS.between(mostRecent.lastUsed(), LocalDateTime.now());
                insights.add("LAST_LOGIN: " + hoursSinceLogin + " hours ago from " + mostRecent.deviceType());
            }
        }
        
        // Account locked
        if (containsAny(queryText, "locked", "blocked", "cant login")) {
            if (response.length() > 0) response.append("\n");
            response.append("Account status: ").append(settings.accountLocked() ? "LOCKED" : "Active");
            if (settings.accountLocked()) {
                insights.add("ALERT: Account is locked");
                insights.add("ACTION: Contact support to unlock");
            }
        }
        
        // Generic security check
        if (response.length() == 0) {
            response.append("Security status: ");
            response.append(settings.twoFactorEnabled() ? "2FA enabled, " : "2FA disabled, ");
            response.append(devices.size()).append(" trusted devices");
            insights.add("SECURITY_SCORE: " + (settings.twoFactorEnabled() ? "good" : "moderate"));
        }
        
        return new AgentContribution(
            getAgentId(),
            response.toString(),
            confidence,
            SupportIntent.SECURITY,
            insights
        );
    }
    
    // ========== ORIGINAL QUERY HANDLERS ==========
    
    private void handleSecurityQuery(Message message) {
        SupportQuery query = extractQuery(message);
        String queryText = query.text().toLowerCase();
        String userId = query.userId();
        
        log.debug("Security query from {}: '{}'", userId, queryText);
        
        SupportResponse response;
        
        if (containsAny(queryText, "password", "reset", "forgot", "change password")) {
            response = handlePasswordQuery(query, userId);
        } else if (containsAny(queryText, "2fa", "two factor", "authenticator", "verification")) {
            response = handle2FAQuery(query, userId);
        } else if (containsAny(queryText, "device", "logged in", "session", "trusted")) {
            response = handleDeviceQuery(query, userId);
        } else if (containsAny(queryText, "locked", "blocked", "cant login", "access denied")) {
            response = handleLockedAccountQuery(query, userId);
        } else if (containsAny(queryText, "logout", "sign out", "log out")) {
            response = handleLogoutQuery(query);
        } else {
            response = handleSecurityOverview(query, userId);
        }
        
        sendResponse(message, response);
    }
    
    private SupportResponse handlePasswordQuery(SupportQuery query, String userId) {
        var settings = dataService.getSecuritySettingsOpt(userId);
        
        StringBuilder sb = new StringBuilder();
        sb.append("**Password Reset**\n\n");
        
        if (settings.isPresent()) {
            long daysSinceChange = ChronoUnit.DAYS.between(
                settings.get().lastPasswordChange(), LocalDateTime.now());
            
            if (daysSinceChange > 90) {
                sb.append("⚠️ Your password was last changed **").append(daysSinceChange)
                    .append(" days ago**. We recommend updating it.\n\n");
            }
        }
        
        sb.append("""
            To reset your password:
            
            **If you're logged in:**
            1. Go to Settings > Security > Change Password
            2. Enter your current password
            3. Create a new password (8+ chars, number, symbol)
            4. Confirm and save
            
            **If you can't log in:**
            1. On the login screen, tap 'Forgot Password'
            2. Enter your email address
            3. Check email for reset link (valid 1 hour)
            4. Click link and create new password
            
            🔒 After resetting, you'll be logged out of all devices.
            """);
        
        // Simulate initiating reset
        boolean initiated = dataService.initiatePasswordReset(userId);
        if (initiated) {
            sb.append("\n✉️ **I've sent a password reset link to your email.**");
        }
        
        return new SupportResponse(query.sessionId(), sb.toString(), SupportIntent.SECURITY,
            0.95, List.of("Check my email", "I didn't receive it", "Speak to agent"), false);
    }
    
    private SupportResponse handle2FAQuery(SupportQuery query, String userId) {
        return dataService.getSecuritySettingsOpt(userId)
            .map(settings -> {
                StringBuilder sb = new StringBuilder();
                sb.append("**Two-Factor Authentication (2FA)**\n\n");
                
                if (settings.twoFactorEnabled()) {
                    sb.append("✓ **2FA is enabled** using: ")
                        .append(settings.twoFactorMethod().equals("authenticator") 
                            ? "Authenticator App" : "SMS")
                        .append("\n\n");
                    
                    sb.append("""
                        **Options:**
                        • Change 2FA method
                        • View backup codes
                        • Disable 2FA (not recommended)
                        
                        Go to Settings > Security > Two-Factor Authentication
                        """);
                } else {
                    sb.append("⚠️ **2FA is not enabled**\n\n");
                    sb.append("""
                        We strongly recommend enabling 2FA for account security.
                        
                        **To enable:**
                        1. Go to Settings > Security > Two-Factor Authentication
                        2. Choose method:
                           • **Authenticator App** (recommended) - Use Google Authenticator, Authy
                           • **SMS** - Receive codes via text message
                        3. Follow setup instructions
                        4. Save your backup codes!
                        
                        Would you like me to start the setup process?
                        """);
                }
                
                return new SupportResponse(query.sessionId(), sb.toString(), SupportIntent.SECURITY,
                    0.9, List.of("Enable 2FA now", "View backup codes", "Change 2FA method"), false);
            })
            .orElse(createNotFoundResponse(query));
    }
    
    private SupportResponse handleDeviceQuery(SupportQuery query, String userId) {
        return dataService.getSecuritySettingsOpt(userId)
            .map(settings -> {
                StringBuilder sb = new StringBuilder();
                sb.append("**Your Trusted Devices**\n\n");
                
                List<TrustedDevice> devices = settings.trustedDevices();
                
                if (devices.isEmpty()) {
                    sb.append("No devices are currently logged in.");
                } else {
                    for (TrustedDevice device : devices) {
                        String status = isRecent(device.lastUsed()) ? "🟢 Active" : "🔴 Inactive";
                        
                        sb.append(String.format("**%s** (%s)\n", device.deviceName(), device.deviceType()));
                        sb.append(String.format("  %s • Last active: %s\n", 
                            status, device.lastUsed().format(DATETIME_FMT)));
                        sb.append(String.format("  📍 %s\n\n", device.location()));
                        
                        // Flag suspicious devices
                        if (device.location().contains("Unknown")) {
                            sb.append("  ⚠️ **Unrecognized location** - Review this device\n\n");
                        }
                    }
                    
                    sb.append("""
                        **To remove a device:**
                        Settings > Security > Trusted Devices > Swipe left > Remove
                        
                        **To log out everywhere:**
                        Settings > Security > Log Out All Devices
                        """);
                }
                
                return new SupportResponse(query.sessionId(), sb.toString(), SupportIntent.SECURITY,
                    0.9, List.of("Remove unknown device", "Log out all devices", "This looks fine"), false);
            })
            .orElse(createNotFoundResponse(query));
    }
    
    private SupportResponse handleLockedAccountQuery(SupportQuery query, String userId) {
        var settings = dataService.getSecuritySettingsOpt(userId);
        
        StringBuilder sb = new StringBuilder();
        sb.append("**Account Access Issues**\n\n");
        
        if (settings.isPresent() && settings.get().failedLoginAttempts() > 3) {
            sb.append("🔒 Your account may be temporarily locked due to multiple failed login attempts.\n\n");
        }
        
        sb.append("""
            **Common solutions:**
            
            1. **Wrong password** → Use 'Forgot Password' to reset
            2. **2FA code not working** → Ensure device time is correct
            3. **Account locked** → Wait 30 minutes and try again
            4. **Email not recognized** → Check for typos
            
            **Still having trouble?**
            Our security team can help verify your identity and restore access.
            
            🚨 If you suspect unauthorized access, contact us immediately.
            """);
        
        return new SupportResponse(query.sessionId(), sb.toString(), SupportIntent.SECURITY,
            0.85, List.of("Reset my password", "Contact security team", "I suspect fraud"), false);
    }
    
    private SupportResponse handleLogoutQuery(SupportQuery query) {
        String text = """
            **Sign Out Options**
            
            **Sign out of current device:**
            Settings > Sign Out
            
            **Sign out of all devices:**
            Settings > Security > Log Out All Devices
            
            This will:
            • End all active sessions
            • Require password on next login
            • Send you a confirmation email
            
            Do you want me to sign you out of all devices now?
            """;
        
        return new SupportResponse(query.sessionId(), text, SupportIntent.SECURITY,
            0.9, List.of("Sign out everywhere", "Just this device", "Cancel"), false);
    }
    
    private SupportResponse handleSecurityOverview(SupportQuery query, String userId) {
        return dataService.getSecuritySettingsOpt(userId)
            .map(settings -> {
                StringBuilder sb = new StringBuilder();
                sb.append("**Security Overview**\n\n");
                
                // 2FA status
                sb.append(settings.twoFactorEnabled() 
                    ? "✓ Two-factor authentication: **Enabled**\n" 
                    : "⚠️ Two-factor authentication: **Disabled**\n");
                
                // Password age
                long daysSince = ChronoUnit.DAYS.between(
                    settings.lastPasswordChange(), LocalDateTime.now());
                sb.append(String.format("• Password last changed: %d days ago%s\n",
                    daysSince, daysSince > 90 ? " ⚠️" : ""));
                
                // Active devices
                sb.append(String.format("• Trusted devices: %d\n", settings.trustedDevices().size()));
                
                // Security score
                int score = calculateSecurityScore(settings);
                sb.append(String.format("\n**Security Score: %d/100** %s\n",
                    score, score >= 80 ? "🟢" : (score >= 50 ? "🟡" : "🔴")));
                
                if (score < 80) {
                    sb.append("\n**Recommendations:**\n");
                    if (!settings.twoFactorEnabled()) {
                        sb.append("• Enable two-factor authentication\n");
                    }
                    if (daysSince > 90) {
                        sb.append("• Update your password\n");
                    }
                }
                
                return new SupportResponse(query.sessionId(), sb.toString(), SupportIntent.SECURITY,
                    0.85, List.of("Improve security", "View devices", "Change password"), false);
            })
            .orElse(createNotFoundResponse(query));
    }
    
    private int calculateSecurityScore(SecuritySettings settings) {
        int score = 50; // Base score
        
        if (settings.twoFactorEnabled()) score += 30;
        
        long daysSince = ChronoUnit.DAYS.between(
            settings.lastPasswordChange(), LocalDateTime.now());
        if (daysSince < 90) score += 20;
        else if (daysSince < 180) score += 10;
        
        return Math.min(100, score);
    }
    
    private boolean isRecent(LocalDateTime time) {
        return ChronoUnit.HOURS.between(time, LocalDateTime.now()) < 24;
    }
    
    private SupportResponse createNotFoundResponse(SupportQuery query) {
        return SupportResponse.simple(query.sessionId(),
            "I couldn't retrieve your security settings. Please try again or contact support.",
            SupportIntent.SECURITY);
    }
    
    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }
    
    private void sendResponse(Message originalMessage, SupportResponse response) {
        Message responseMsg = Message.builder()
            .topic("support.response")
            .senderId(getAgentId())
            .receiverId(originalMessage.senderId())
            .correlationId(response.sessionId())
            .content(response)
            .build();
        getMessageDispatcher().sendTo(responseMsg.receiverId(), responseMsg);
    }
    
    private SupportQuery extractQuery(Message message) {
        Object content = message.content();
        if (content instanceof SupportQuery q) return q;
        String text = content instanceof String s ? s : content.toString();
        return new SupportQuery(
            message.correlationId() != null ? message.correlationId() : message.id(),
            message.senderId(),
            text
        );
    }
}
