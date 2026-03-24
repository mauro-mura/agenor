package dev.jentic.runtime.hitl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.jentic.core.hitl.ApprovalNotifier;
import dev.jentic.core.hitl.ApprovalRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@link ApprovalNotifier} that sends an HTTP POST to a configurable webhook URL.
 *
 * <p>The request body is the JSON serialisation of {@link ApprovalRequest} via
 * Jackson. The call runs on a virtual thread (fire-and-forget) so it never blocks
 * the agent's execution thread.
 *
 * <p>Retry policy: up to {@value #DEFAULT_MAX_RETRIES} attempts with exponential
 * backoff starting at {@value #DEFAULT_BASE_DELAY_MS} ms (doubled each attempt).
 * On 5xx responses or connection timeouts the attempt is retried; on 4xx or after
 * all retries are exhausted, the failure is logged and swallowed — the agent is
 * never interrupted.
 *
 * <p>Usage with custom headers (e.g. Bearer token):
 * <pre>{@code
 * var notifier = WebhookApprovalNotifier.builder()
 *     .url("https://approval.example.com/webhooks/hitl")
 *     .header("Authorization", "Bearer " + token)
 *     .connectTimeout(Duration.ofSeconds(3))
 *     .build();
 * }</pre>
 *
 * @see ApprovalNotifier
 * @since 0.13.0
 */
public class WebhookApprovalNotifier implements ApprovalNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookApprovalNotifier.class);

    static final int DEFAULT_MAX_RETRIES = 3;
    static final long DEFAULT_BASE_DELAY_MS = 200L;
    static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final String url;
    private final Map<String, String> headers;
    private final int maxRetries;
    private final long baseDelayMs;
    private final Duration requestTimeout;
    private final HttpClient httpClient;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /** No-arg constructor for {@code @RequiresApproval} annotation wiring. Requires {@code HITL_WEBHOOK_URL} env var. */
    public WebhookApprovalNotifier() {
        this(System.getenv().getOrDefault("HITL_WEBHOOK_URL", "http://localhost:8080/hitl/approval"),
                Collections.emptyMap(), DEFAULT_MAX_RETRIES, DEFAULT_BASE_DELAY_MS,
                DEFAULT_CONNECT_TIMEOUT, DEFAULT_REQUEST_TIMEOUT);
    }

    private WebhookApprovalNotifier(String url, Map<String, String> headers,
                                     int maxRetries, long baseDelayMs,
                                     Duration connectTimeout, Duration requestTimeout) {
        this.url = Objects.requireNonNull(url, "url must not be null");
        this.headers = Collections.unmodifiableMap(new HashMap<>(headers));
        this.maxRetries = maxRetries;
        this.baseDelayMs = baseDelayMs;
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout must not be null");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
    }

    // -------------------------------------------------------------------------
    // ApprovalNotifier
    // -------------------------------------------------------------------------

    /**
     * Serialises {@code request} to JSON and POSTs it to the configured URL on a
     * virtual thread. Returns immediately; the agent is never blocked.
     */
    @Override
    public void notify(ApprovalRequest request) {
        String body;
        try {
            body = MAPPER.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialise ApprovalRequest to JSON: requestId={}",
                    request.requestId(), e);
            return;
        }

        final String capturedBody = body;
        Thread.ofVirtual()
                .name("hitl-webhook-" + request.requestId())
                .start(() -> sendWithRetry(request.requestId(), capturedBody));
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    void sendWithRetry(String requestId, String body) {
        long delayMs = baseDelayMs;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .timeout(requestTimeout);

                headers.forEach(builder::header);

                HttpResponse<Void> response = httpClient.send(
                        builder.build(), HttpResponse.BodyHandlers.discarding());

                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    log.debug("Webhook notified: requestId={}, status={}, attempt={}",
                            requestId, status, attempt);
                    return;
                }
                if (status >= 400 && status < 500) {
                    log.error("Webhook rejected ({}): requestId={} — no retry for 4xx",
                            status, requestId);
                    return;
                }
                // 5xx: retry
                log.warn("Webhook returned {}: requestId={}, attempt={}/{} — retrying in {}ms",
                        status, requestId, attempt, maxRetries, delayMs);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Webhook interrupted: requestId={}", requestId);
                return;
            } catch (Exception e) {
                log.warn("Webhook error: requestId={}, attempt={}/{} — {}",
                        requestId, attempt, maxRetries, e.getMessage());
            }

            if (attempt < maxRetries) {
                sleep(delayMs);
                delayMs *= 2; // exponential backoff
            }
        }
        log.error("Webhook failed after {} attempts: requestId={}", maxRetries, requestId);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String url;
        private final Map<String, String> headers = new HashMap<>();
        private int maxRetries = DEFAULT_MAX_RETRIES;
        private long baseDelayMs = DEFAULT_BASE_DELAY_MS;
        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;

        private Builder() {}

        public Builder url(String url) {
            this.url = url;
            return this;
        }

        public Builder header(String name, String value) {
            this.headers.put(name, value);
            return this;
        }

        public Builder headers(Map<String, String> headers) {
            this.headers.putAll(headers);
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            if (maxRetries < 1) throw new IllegalArgumentException("maxRetries must be >= 1");
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder baseDelayMs(long baseDelayMs) {
            if (baseDelayMs < 0) throw new IllegalArgumentException("baseDelayMs must be >= 0");
            this.baseDelayMs = baseDelayMs;
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout must not be null");
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = Objects.requireNonNull(connectTimeout);
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = Objects.requireNonNull(requestTimeout);
            return this;
        }

        public WebhookApprovalNotifier build() {
            Objects.requireNonNull(url, "url must be set");
            return new WebhookApprovalNotifier(url, headers, maxRetries, baseDelayMs,
                    connectTimeout, requestTimeout);
        }
    }
}