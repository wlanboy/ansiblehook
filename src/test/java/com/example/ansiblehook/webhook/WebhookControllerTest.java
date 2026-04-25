package com.example.ansiblehook.webhook;

import com.example.ansiblehook.AnsiblehookProperties;
import com.example.ansiblehook.WebhookProperties;
import com.example.ansiblehook.ansible.AnsibleService;
import com.example.ansiblehook.ansible.PlaybookAlreadyRunningException;
import com.example.ansiblehook.ansible.PlaybookFailedException;
import com.example.ansiblehook.ott.TokenService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebhookControllerTest {

    private static final String SECRET = "test-secret";
    private static final String WEBHOOK_ID = "test";

    private WebhookController controller;
    private AnsibleService ansibleService;
    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        AnsiblehookProperties props = mock(AnsiblehookProperties.class);
        when(props.webhooks()).thenReturn(Map.of(WEBHOOK_ID,
                new WebhookProperties(SECRET, "/tmp", "hosts", "site.yml",
                        null, null, null, null)));

        ansibleService = mock(AnsibleService.class);
        tokenService = new TokenService();
        controller = new WebhookController(props, ansibleService, tokenService);
    }

    // ------------------------------------------------------------
    // status — 404
    // ------------------------------------------------------------

    @Test
    void status_returnsNotFoundForUnknownWebhook() {
        var response = controller.status("unknown", ts(), hmac(SECRET, ts()), null);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    // ------------------------------------------------------------
    // status — HMAC auth
    // ------------------------------------------------------------

    @Test
    void status_returns200_withValidHmac() {
        when(ansibleService.isRunning(WEBHOOK_ID)).thenReturn(false);
        String ts = ts();
        var response = controller.status(WEBHOOK_ID, ts, hmac(SECRET, ts), null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("running", false);
    }

    @Test
    void status_returns401_withInvalidSignature() {
        var response = controller.status(WEBHOOK_ID, ts(), "sha256=deadbeef", null);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void status_returns401_withExpiredTimestamp() {
        String old = String.valueOf(Instant.now().minus(Duration.ofMinutes(10)).getEpochSecond());
        var response = controller.status(WEBHOOK_ID, old, hmac(SECRET, old), null);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void status_returns401_withNoAuthHeaders() {
        var response = controller.status(WEBHOOK_ID, null, null, null);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    // ------------------------------------------------------------
    // status — one-time token
    // ------------------------------------------------------------

    @Test
    void status_returns200_withValidToken() {
        when(ansibleService.isRunning(WEBHOOK_ID)).thenReturn(true);
        String token = tokenService.create(WEBHOOK_ID, Instant.now(), TokenService.DEFAULT_TTL).token();
        var response = controller.status(WEBHOOK_ID, null, null, token);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("running", true);
    }

    @Test
    void status_returns401_withInvalidToken() {
        var response = controller.status(WEBHOOK_ID, null, null, "invalid-token");
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void status_tokenIsConsumedAfterStatusCheck() {
        String token = tokenService.create(WEBHOOK_ID, Instant.now(), TokenService.DEFAULT_TTL).token();
        when(ansibleService.isRunning(WEBHOOK_ID)).thenReturn(false);
        controller.status(WEBHOOK_ID, null, null, token);
        var second = controller.status(WEBHOOK_ID, null, null, token);
        assertThat(second.getStatusCode().value()).isEqualTo(401);
    }

    // ------------------------------------------------------------
    // createToken — 404
    // ------------------------------------------------------------

    @Test
    void createToken_returnsNotFoundForUnknownWebhook() {
        var response = controller.createToken("unknown", ts(), hmac(SECRET, ts()), 3600, null);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    // ------------------------------------------------------------
    // createToken — auth
    // ------------------------------------------------------------

    @Test
    void createToken_returns200_withValidHmac() {
        String ts = ts();
        var response = controller.createToken(WEBHOOK_ID, ts, hmac(SECRET, ts), 3600, null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsKey("token");
        assertThat(response.getBody()).containsKey("expires_at");
        assertThat(response.getBody()).containsEntry("webhook_id", WEBHOOK_ID);
    }

    @Test
    void createToken_returns401_withInvalidSignature() {
        var response = controller.createToken(WEBHOOK_ID, ts(), "sha256=deadbeef", 3600, null);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void createToken_returns401_withExpiredTimestamp() {
        String old = String.valueOf(Instant.now().minus(Duration.ofMinutes(10)).getEpochSecond());
        var response = controller.createToken(WEBHOOK_ID, old, hmac(SECRET, old), 3600, null);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void createToken_includesNotBeforeInResponse() {
        String ts = ts();
        String notBefore = Instant.now().plusSeconds(300).toString();
        var response = controller.createToken(WEBHOOK_ID, ts, hmac(SECRET, ts), 3600, notBefore);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("not_before", notBefore);
    }

    @Test
    void createToken_returns400_forInvalidNotBefore() {
        String ts = ts();
        var response = controller.createToken(WEBHOOK_ID, ts, hmac(SECRET, ts), 3600, "not-a-date");
        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void createToken_defaultsNotBeforeToNow_whenAbsent() {
        String ts = ts();
        var response = controller.createToken(WEBHOOK_ID, ts, hmac(SECRET, ts), 3600, null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Instant notBefore = Instant.parse((String) response.getBody().get("not_before"));
        assertThat(notBefore).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void createToken_clampsTtlToMaximum() {
        String ts = ts();
        var response = controller.createToken(WEBHOOK_ID, ts, hmac(SECRET, ts), 999_999L, null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Instant expiresAt = Instant.parse((String) response.getBody().get("expires_at"));
        Duration actualTtl = Duration.between(Instant.now(), expiresAt);
        assertThat(actualTtl).isLessThanOrEqualTo(Duration.ofSeconds(86400));
    }

    @Test
    void createToken_clampsZeroTtlToOne() {
        String ts = ts();
        var response = controller.createToken(WEBHOOK_ID, ts, hmac(SECRET, ts), 0L, null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Instant expiresAt = Instant.parse((String) response.getBody().get("expires_at"));
        assertThat(expiresAt).isAfter(Instant.now());
    }

    // ------------------------------------------------------------
    // trigger — 404
    // ------------------------------------------------------------

    @Test
    void trigger_returnsNotFound_forUnknownWebhook() {
        String ts = ts();
        var response = controller.trigger("unknown", ts, hmac(SECRET, ts), null).block();
        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    // ------------------------------------------------------------
    // trigger — HMAC auth
    // ------------------------------------------------------------

    @Test
    void trigger_returns200_withValidHmac() {
        when(ansibleService.execute(eq(WEBHOOK_ID), any())).thenReturn(Mono.just("ok"));
        String ts = ts();
        var response = controller.trigger(WEBHOOK_ID, ts, hmac(SECRET, ts), null).block();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void trigger_returns401_withInvalidSignature() {
        var response = controller.trigger(WEBHOOK_ID, ts(), "sha256=deadbeef", null).block();
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void trigger_returns401_withExpiredTimestamp() {
        String old = String.valueOf(Instant.now().minus(Duration.ofMinutes(10)).getEpochSecond());
        var response = controller.trigger(WEBHOOK_ID, old, hmac(SECRET, old), null).block();
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void trigger_returns401_withNoAuthHeaders() {
        var response = controller.trigger(WEBHOOK_ID, null, null, null).block();
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    // ------------------------------------------------------------
    // trigger — one-time token
    // ------------------------------------------------------------

    @Test
    void trigger_returns200_withValidToken() {
        when(ansibleService.execute(eq(WEBHOOK_ID), any())).thenReturn(Mono.just("ok"));
        String token = tokenService.create(WEBHOOK_ID, Instant.now(), TokenService.DEFAULT_TTL).token();
        var response = controller.trigger(WEBHOOK_ID, null, null, token).block();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void trigger_returns401_withInvalidToken() {
        var response = controller.trigger(WEBHOOK_ID, null, null, "invalid-token").block();
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void trigger_tokenIsConsumedAfterUse() {
        when(ansibleService.execute(eq(WEBHOOK_ID), any())).thenReturn(Mono.just("ok"));
        String token = tokenService.create(WEBHOOK_ID, Instant.now(), TokenService.DEFAULT_TTL).token();
        controller.trigger(WEBHOOK_ID, null, null, token).block();
        var second = controller.trigger(WEBHOOK_ID, null, null, token).block();
        assertThat(second.getStatusCode().value()).isEqualTo(401);
    }

    // ------------------------------------------------------------
    // trigger — AnsibleService error propagation
    // ------------------------------------------------------------

    @Test
    void trigger_returns409_whenPlaybookAlreadyRunning() {
        when(ansibleService.execute(eq(WEBHOOK_ID), any()))
                .thenReturn(Mono.error(new PlaybookAlreadyRunningException(WEBHOOK_ID)));
        String ts = ts();
        var response = controller.trigger(WEBHOOK_ID, ts, hmac(SECRET, ts), null).block();
        assertThat(response.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void trigger_returns500_whenPlaybookFails() {
        when(ansibleService.execute(eq(WEBHOOK_ID), any()))
                .thenReturn(Mono.error(new PlaybookFailedException("error output", 1)));
        String ts = ts();
        var response = controller.trigger(WEBHOOK_ID, ts, hmac(SECRET, ts), null).block();
        assertThat(response.getStatusCode().value()).isEqualTo(500);
    }

    // ------------------------------------------------------------
    // Hilfsmethoden
    // ------------------------------------------------------------

    private static String ts() {
        return String.valueOf(Instant.now().getEpochSecond());
    }

    static String hmac(String secret, String message) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
