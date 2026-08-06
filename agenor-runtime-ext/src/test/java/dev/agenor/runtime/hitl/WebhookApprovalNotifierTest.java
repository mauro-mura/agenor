package dev.agenor.runtime.hitl;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.agenor.core.hitl.ApprovalRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WebhookApprovalNotifier")
class WebhookApprovalNotifierTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private WebhookApprovalNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = WebhookApprovalNotifier.builder()
                .url(wm.baseUrl() + "/hitl/approval")
                .baseDelayMs(50)
                .build();
    }

    private ApprovalRequest req() {
        return ApprovalRequest.of("agent-1", "process-payment", "payload", Duration.ofMinutes(5));
    }

    /** Polls until WireMock has received {@code expected} requests or timeoutMs elapses. */
    private void waitForRequests(int expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (wm.findAll(postRequestedFor(urlEqualTo("/hitl/approval"))).size() >= expected) break;
            Thread.sleep(50);
        }
        wm.verify(expected, postRequestedFor(urlEqualTo("/hitl/approval")));
    }

    @Nested
    @DisplayName("HTTP 200 — success")
    class SuccessTests {

        @Test
        @DisplayName("200 → no retry, exactly 1 request with Content-Type: application/json")
        void success_noRetry() throws InterruptedException {
            wm.stubFor(post(urlEqualTo("/hitl/approval")).willReturn(aResponse().withStatus(200)));
            notifier.notify(req());
            waitForRequests(1, 3_000);
            wm.verify(1, postRequestedFor(urlEqualTo("/hitl/approval"))
                    .withHeader("Content-Type", containing("application/json")));
        }

        @Test
        @DisplayName("body contains requestId and action name")
        void success_bodyContainsFields() throws InterruptedException {
            var request = req();
            wm.stubFor(post(urlEqualTo("/hitl/approval")).willReturn(aResponse().withStatus(200)));
            notifier.notify(request);
            waitForRequests(1, 3_000);
            wm.verify(1, postRequestedFor(urlEqualTo("/hitl/approval"))
                    .withRequestBody(containing(request.requestId()))
                    .withRequestBody(containing("process-payment")));
        }

        @Test
        @DisplayName("custom Authorization header is forwarded")
        void success_customHeader() throws InterruptedException {
            wm.stubFor(post(urlEqualTo("/hitl/approval")).willReturn(aResponse().withStatus(200)));
            var n = WebhookApprovalNotifier.builder()
                    .url(wm.baseUrl() + "/hitl/approval")
                    .header("Authorization", "Bearer test-token")
                    .baseDelayMs(50).build();
            n.notify(req());
            waitForRequests(1, 3_000);
            wm.verify(1, postRequestedFor(urlEqualTo("/hitl/approval"))
                    .withHeader("Authorization", equalTo("Bearer test-token")));
        }
    }

    @Nested
    @DisplayName("HTTP 503 — retry")
    class RetryTests {

        @Test
        @DisplayName("503 → exactly 3 attempts (maxRetries default)")
        void serverError_retriesThreeTimes() throws InterruptedException {
            wm.stubFor(post(urlEqualTo("/hitl/approval")).willReturn(aResponse().withStatus(503)));
            notifier.notify(req());
            waitForRequests(3, 5_000);
        }

        @Test
        @DisplayName("503, 503, 200 → success on third attempt")
        void partialFailure_succeedsOnThird() throws InterruptedException {
            wm.stubFor(post(urlEqualTo("/hitl/approval"))
                    .inScenario("flaky").whenScenarioStateIs("Started")
                    .willReturn(aResponse().withStatus(503)).willSetStateTo("s2"));
            wm.stubFor(post(urlEqualTo("/hitl/approval"))
                    .inScenario("flaky").whenScenarioStateIs("s2")
                    .willReturn(aResponse().withStatus(503)).willSetStateTo("s3"));
            wm.stubFor(post(urlEqualTo("/hitl/approval"))
                    .inScenario("flaky").whenScenarioStateIs("s3")
                    .willReturn(aResponse().withStatus(200)));
            notifier.notify(req());
            waitForRequests(3, 5_000);
        }
    }

    @Nested
    @DisplayName("HTTP 4xx — no retry")
    class ClientErrorTests {

        @Test
        @DisplayName("400 → exactly 1 request sent, no retry")
        void clientError_noRetry() throws InterruptedException {
            wm.stubFor(post(urlEqualTo("/hitl/approval")).willReturn(aResponse().withStatus(400)));
            notifier.notify(req());
            waitForRequests(1, 3_000);
            Thread.sleep(300); // ensure no additional requests arrive
            wm.verify(1, postRequestedFor(urlEqualTo("/hitl/approval")));
        }
    }

    @Nested
    @DisplayName("Request timeout — retry")
    class TimeoutTests {

        @Test
        @DisplayName("response delayed beyond requestTimeout → 3 retries, no exception to caller")
        void requestTimeout_retriesWithoutThrowing() throws InterruptedException {
            wm.stubFor(post(urlEqualTo("/hitl/approval"))
                    .willReturn(aResponse().withStatus(200).withFixedDelay(2_000)));
            var fast = WebhookApprovalNotifier.builder()
                    .url(wm.baseUrl() + "/hitl/approval")
                    .baseDelayMs(50).maxRetries(3)
                    .requestTimeout(Duration.ofMillis(300))
                    .build();
            fast.notify(req()); // must not throw
            waitForRequests(3, 8_000);
        }
    }

    @Test
    @DisplayName("builder without url throws NullPointerException")
    void builder_noUrl_throws() {
        assertThatThrownBy(() -> WebhookApprovalNotifier.builder().build())
                .isInstanceOf(NullPointerException.class);
    }
}
