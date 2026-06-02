package dev.agenor.examples.support.production;

import dev.agenor.examples.support.production.LanguageDetector.Language;

import java.util.*;

/**
 * Provides localized messages for the support chatbot.
 * Supports English, Italian, Spanish, French, German, Portuguese.
 */
public class LocalizationService {

    private final Map<Language, Map<String, String>> translations = new HashMap<>();
    private final Language defaultLanguage;

    public LocalizationService() {
        this(Language.ENGLISH);
    }

    public LocalizationService(Language defaultLanguage) {
        this.defaultLanguage = defaultLanguage;
        initializeTranslations();
    }

    private void initializeTranslations() {
        // English (base)
        translations.put(Language.ENGLISH, Map.ofEntries(
            // Greetings
            Map.entry("welcome", "👋 **Welcome to FinanceCloud Support!**"),
            Map.entry("welcome.subtitle", "I'm here to help you with:"),
            Map.entry("goodbye", "Thank you for contacting us. Have a great day!"),

            // Categories
            Map.entry("category.account", "Account - Balance, profile, linked accounts"),
            Map.entry("category.transactions", "Transactions - History, exports, disputes"),
            Map.entry("category.security", "Security - Password, 2FA, devices"),
            Map.entry("category.budgets", "Budgets - Create and track spending limits"),

            // Common responses
            Map.entry("ask.how_help", "How can I assist you today?"),
            Map.entry("ask.anything_else", "Is there anything else I can help you with?"),
            Map.entry("confirm.understood", "I understand. Let me help you with that."),
            Map.entry("apologize.confusion", "I'm sorry for any confusion."),
            Map.entry("apologize.inconvenience", "I apologize for the inconvenience."),

            // Errors
            Map.entry("error.not_found", "I couldn't find specific information about your question."),
            Map.entry("error.try_again", "Could you please rephrase your question?"),
            Map.entry("error.technical", "I encountered a technical issue. Please try again."),

            // Escalation
            Map.entry("escalation.connecting", "I'm connecting you with a human agent."),
            Map.entry("escalation.wait", "Please wait, your estimated wait time is"),
            Map.entry("escalation.queue", "You are number %d in the queue."),
            Map.entry("escalation.callback", "We'll call you back at the number provided."),
            Map.entry("escalation.email", "We've sent the details to your email."),
            Map.entry("escalation.hours", "Our support hours are 9 AM - 6 PM."),

            // Satisfaction
            Map.entry("satisfaction.ask", "Was this helpful? Please rate 1-5."),
            Map.entry("satisfaction.thanks", "Thank you for your feedback!"),

            // Actions
            Map.entry("action.view_balance", "View my balance"),
            Map.entry("action.reset_password", "Reset password"),
            Map.entry("action.recent_transactions", "Recent transactions"),
            Map.entry("action.speak_agent", "Speak to an agent"),

            // Time
            Map.entry("time.minutes", "minutes"),
            Map.entry("time.hours", "hours")
        ));

        // Italian
        translations.put(Language.ITALIAN, Map.ofEntries(
            Map.entry("welcome", "👋 **Benvenuto al Supporto FinanceCloud!**"),
            Map.entry("welcome.subtitle", "Sono qui per aiutarti con:"),
            Map.entry("goodbye", "Grazie per averci contattato. Buona giornata!"),

            Map.entry("category.account", "Conto - Saldo, profilo, conti collegati"),
            Map.entry("category.transactions", "Transazioni - Cronologia, esportazioni, contestazioni"),
            Map.entry("category.security", "Sicurezza - Password, 2FA, dispositivi"),
            Map.entry("category.budgets", "Budget - Crea e monitora i limiti di spesa"),

            Map.entry("ask.how_help", "Come posso aiutarti oggi?"),
            Map.entry("ask.anything_else", "C'è altro in cui posso aiutarti?"),
            Map.entry("confirm.understood", "Capisco. Lascia che ti aiuti."),
            Map.entry("apologize.confusion", "Mi scuso per la confusione."),
            Map.entry("apologize.inconvenience", "Mi scuso per l'inconveniente."),

            Map.entry("error.not_found", "Non ho trovato informazioni specifiche sulla tua domanda."),
            Map.entry("error.try_again", "Potresti riformulare la tua domanda?"),
            Map.entry("error.technical", "Ho riscontrato un problema tecnico. Riprova."),

            Map.entry("escalation.connecting", "Ti sto collegando con un operatore."),
            Map.entry("escalation.wait", "Attendi, il tempo stimato è"),
            Map.entry("escalation.queue", "Sei il numero %d in coda."),
            Map.entry("escalation.callback", "Ti richiameremo al numero fornito."),
            Map.entry("escalation.email", "Abbiamo inviato i dettagli alla tua email."),
            Map.entry("escalation.hours", "Il nostro orario è 9:00 - 18:00."),

            Map.entry("satisfaction.ask", "Ti è stato utile? Valuta da 1 a 5."),
            Map.entry("satisfaction.thanks", "Grazie per il tuo feedback!"),

            Map.entry("action.view_balance", "Visualizza saldo"),
            Map.entry("action.reset_password", "Reimposta password"),
            Map.entry("action.recent_transactions", "Transazioni recenti"),
            Map.entry("action.speak_agent", "Parla con un operatore"),

            Map.entry("time.minutes", "minuti"),
            Map.entry("time.hours", "ore")
        ));

        // Spanish
        translations.put(Language.SPANISH, Map.ofEntries(
            Map.entry("welcome", "👋 **¡Bienvenido al Soporte de FinanceCloud!**"),
            Map.entry("welcome.subtitle", "Estoy aquí para ayudarte con:"),
            Map.entry("goodbye", "Gracias por contactarnos. ¡Que tengas un buen día!"),

            Map.entry("category.account", "Cuenta - Saldo, perfil, cuentas vinculadas"),
            Map.entry("category.transactions", "Transacciones - Historial, exportaciones, disputas"),
            Map.entry("category.security", "Seguridad - Contraseña, 2FA, dispositivos"),
            Map.entry("category.budgets", "Presupuestos - Crear y seguir límites de gastos"),

            Map.entry("ask.how_help", "¿Cómo puedo ayudarte hoy?"),
            Map.entry("ask.anything_else", "¿Hay algo más en lo que pueda ayudarte?"),
            Map.entry("confirm.understood", "Entiendo. Déjame ayudarte con eso."),
            Map.entry("apologize.confusion", "Disculpa por la confusión."),
            Map.entry("apologize.inconvenience", "Disculpa las molestias."),

            Map.entry("error.not_found", "No pude encontrar información sobre tu pregunta."),
            Map.entry("error.try_again", "¿Podrías reformular tu pregunta?"),
            Map.entry("error.technical", "Encontré un problema técnico. Intenta de nuevo."),

            Map.entry("escalation.connecting", "Te estoy conectando con un agente."),
            Map.entry("escalation.wait", "Por favor espera, el tiempo estimado es"),
            Map.entry("escalation.queue", "Eres el número %d en la cola."),
            Map.entry("escalation.callback", "Te llamaremos al número proporcionado."),
            Map.entry("escalation.email", "Hemos enviado los detalles a tu email."),
            Map.entry("escalation.hours", "Nuestro horario es 9:00 - 18:00."),

            Map.entry("satisfaction.ask", "¿Te fue útil? Califica del 1 al 5."),
            Map.entry("satisfaction.thanks", "¡Gracias por tu opinión!"),

            Map.entry("action.view_balance", "Ver mi saldo"),
            Map.entry("action.reset_password", "Restablecer contraseña"),
            Map.entry("action.recent_transactions", "Transacciones recientes"),
            Map.entry("action.speak_agent", "Hablar con un agente"),

            Map.entry("time.minutes", "minutos"),
            Map.entry("time.hours", "horas")
        ));

        // French
        translations.put(Language.FRENCH, Map.ofEntries(
            Map.entry("welcome", "👋 **Bienvenue au Support FinanceCloud !**"),
            Map.entry("welcome.subtitle", "Je suis là pour vous aider avec :"),
            Map.entry("goodbye", "Merci de nous avoir contactés. Bonne journée !"),

            Map.entry("category.account", "Compte - Solde, profil, comptes liés"),
            Map.entry("category.transactions", "Transactions - Historique, exports, litiges"),
            Map.entry("category.security", "Sécurité - Mot de passe, 2FA, appareils"),
            Map.entry("category.budgets", "Budgets - Créer et suivre les limites de dépenses"),

            Map.entry("ask.how_help", "Comment puis-je vous aider aujourd'hui ?"),
            Map.entry("ask.anything_else", "Y a-t-il autre chose que je puisse faire ?"),
            Map.entry("confirm.understood", "Je comprends. Laissez-moi vous aider."),
            Map.entry("apologize.confusion", "Je m'excuse pour la confusion."),
            Map.entry("apologize.inconvenience", "Je m'excuse pour le désagrément."),

            Map.entry("error.not_found", "Je n'ai pas trouvé d'information sur votre question."),
            Map.entry("error.try_again", "Pourriez-vous reformuler votre question ?"),
            Map.entry("error.technical", "J'ai rencontré un problème technique. Réessayez."),

            Map.entry("escalation.connecting", "Je vous connecte à un agent."),
            Map.entry("escalation.wait", "Veuillez patienter, le temps estimé est"),
            Map.entry("escalation.queue", "Vous êtes le numéro %d dans la file."),
            Map.entry("escalation.callback", "Nous vous rappellerons au numéro fourni."),
            Map.entry("escalation.email", "Nous avons envoyé les détails à votre email."),
            Map.entry("escalation.hours", "Nos horaires sont 9h - 18h."),

            Map.entry("satisfaction.ask", "Cela vous a-t-il aidé ? Notez de 1 à 5."),
            Map.entry("satisfaction.thanks", "Merci pour votre retour !"),

            Map.entry("action.view_balance", "Voir mon solde"),
            Map.entry("action.reset_password", "Réinitialiser mot de passe"),
            Map.entry("action.recent_transactions", "Transactions récentes"),
            Map.entry("action.speak_agent", "Parler à un agent"),

            Map.entry("time.minutes", "minutes"),
            Map.entry("time.hours", "heures")
        ));

        // German
        translations.put(Language.GERMAN, Map.ofEntries(
            Map.entry("welcome", "👋 **Willkommen beim FinanceCloud Support!**"),
            Map.entry("welcome.subtitle", "Ich bin hier, um Ihnen zu helfen mit:"),
            Map.entry("goodbye", "Danke für Ihre Kontaktaufnahme. Schönen Tag noch!"),

            Map.entry("category.account", "Konto - Saldo, Profil, verknüpfte Konten"),
            Map.entry("category.transactions", "Transaktionen - Verlauf, Exporte, Streitigkeiten"),
            Map.entry("category.security", "Sicherheit - Passwort, 2FA, Geräte"),
            Map.entry("category.budgets", "Budgets - Ausgabenlimits erstellen und verfolgen"),

            Map.entry("ask.how_help", "Wie kann ich Ihnen heute helfen?"),
            Map.entry("ask.anything_else", "Kann ich sonst noch etwas für Sie tun?"),
            Map.entry("confirm.understood", "Verstanden. Lassen Sie mich Ihnen helfen."),
            Map.entry("apologize.confusion", "Entschuldigung für die Verwirrung."),
            Map.entry("apologize.inconvenience", "Entschuldigung für die Unannehmlichkeiten."),

            Map.entry("error.not_found", "Ich konnte keine Informationen zu Ihrer Frage finden."),
            Map.entry("error.try_again", "Könnten Sie Ihre Frage umformulieren?"),
            Map.entry("error.technical", "Es gab ein technisches Problem. Bitte versuchen Sie es erneut."),

            Map.entry("escalation.connecting", "Ich verbinde Sie mit einem Mitarbeiter."),
            Map.entry("escalation.wait", "Bitte warten Sie, die geschätzte Wartezeit ist"),
            Map.entry("escalation.queue", "Sie sind Nummer %d in der Warteschlange."),
            Map.entry("escalation.callback", "Wir rufen Sie unter der angegebenen Nummer zurück."),
            Map.entry("escalation.email", "Wir haben die Details an Ihre E-Mail gesendet."),
            Map.entry("escalation.hours", "Unsere Servicezeiten sind 9:00 - 18:00 Uhr."),

            Map.entry("satisfaction.ask", "War das hilfreich? Bewerten Sie 1-5."),
            Map.entry("satisfaction.thanks", "Danke für Ihr Feedback!"),

            Map.entry("action.view_balance", "Saldo anzeigen"),
            Map.entry("action.reset_password", "Passwort zurücksetzen"),
            Map.entry("action.recent_transactions", "Letzte Transaktionen"),
            Map.entry("action.speak_agent", "Mit Mitarbeiter sprechen"),

            Map.entry("time.minutes", "Minuten"),
            Map.entry("time.hours", "Stunden")
        ));

        // Portuguese
        translations.put(Language.PORTUGUESE, Map.ofEntries(
            Map.entry("welcome", "👋 **Bem-vindo ao Suporte FinanceCloud!**"),
            Map.entry("welcome.subtitle", "Estou aqui para ajudá-lo com:"),
            Map.entry("goodbye", "Obrigado por nos contactar. Tenha um bom dia!"),

            Map.entry("category.account", "Conta - Saldo, perfil, contas vinculadas"),
            Map.entry("category.transactions", "Transações - Histórico, exportações, disputas"),
            Map.entry("category.security", "Segurança - Senha, 2FA, dispositivos"),
            Map.entry("category.budgets", "Orçamentos - Criar e acompanhar limites de gastos"),

            Map.entry("ask.how_help", "Como posso ajudá-lo hoje?"),
            Map.entry("ask.anything_else", "Há mais alguma coisa em que posso ajudar?"),
            Map.entry("confirm.understood", "Entendo. Deixe-me ajudá-lo com isso."),
            Map.entry("apologize.confusion", "Desculpe pela confusão."),
            Map.entry("apologize.inconvenience", "Desculpe pelo inconveniente."),

            Map.entry("error.not_found", "Não encontrei informações sobre sua pergunta."),
            Map.entry("error.try_again", "Poderia reformular sua pergunta?"),
            Map.entry("error.technical", "Ocorreu um problema técnico. Tente novamente."),

            Map.entry("escalation.connecting", "Estou conectando você a um atendente."),
            Map.entry("escalation.wait", "Por favor aguarde, o tempo estimado é"),
            Map.entry("escalation.queue", "Você é o número %d na fila."),
            Map.entry("escalation.callback", "Ligaremos de volta para o número fornecido."),
            Map.entry("escalation.email", "Enviamos os detalhes para seu email."),
            Map.entry("escalation.hours", "Nosso horário é 9h - 18h."),

            Map.entry("satisfaction.ask", "Isso foi útil? Avalie de 1 a 5."),
            Map.entry("satisfaction.thanks", "Obrigado pelo seu feedback!"),

            Map.entry("action.view_balance", "Ver meu saldo"),
            Map.entry("action.reset_password", "Redefinir senha"),
            Map.entry("action.recent_transactions", "Transações recentes"),
            Map.entry("action.speak_agent", "Falar com atendente"),

            Map.entry("time.minutes", "minutos"),
            Map.entry("time.hours", "horas")
        ));
    }

    /**
     * Gets a localized message.
     */
    public String get(String key, Language language) {
        Map<String, String> langMessages = translations.get(language);
        if (langMessages != null && langMessages.containsKey(key)) {
            return langMessages.get(key);
        }

        // Fallback to default language
        Map<String, String> defaultMessages = translations.get(defaultLanguage);
        if (defaultMessages != null && defaultMessages.containsKey(key)) {
            return defaultMessages.get(key);
        }

        return key; // Return key if not found
    }

    /**
     * Gets a formatted localized message.
     */
    public String getFormatted(String key, Language language, Object... args) {
        String template = get(key, language);
        return String.format(template, args);
    }

    /**
     * Gets the welcome message with categories.
     */
    public String getWelcomeMessage(Language language) {
        StringBuilder sb = new StringBuilder();
        sb.append(get("welcome", language)).append("\n\n");
        sb.append(get("welcome.subtitle", language)).append("\n");
        sb.append("• **").append(get("category.account", language)).append("**\n");
        sb.append("• **").append(get("category.transactions", language)).append("**\n");
        sb.append("• **").append(get("category.security", language)).append("**\n");
        sb.append("• **").append(get("category.budgets", language)).append("**\n\n");
        sb.append(get("ask.how_help", language));
        return sb.toString();
    }

    /**
     * Gets localized action buttons.
     */
    public List<String> getActionButtons(Language language) {
        return List.of(
            get("action.view_balance", language),
            get("action.reset_password", language),
            get("action.recent_transactions", language)
        );
    }

    /**
     * Checks if a language is supported.
     */
    public boolean isSupported(Language language) {
        return translations.containsKey(language);
    }

    /**
     * Gets all supported languages.
     */
    public Set<Language> getSupportedLanguages() {
        return translations.keySet();
    }
}
