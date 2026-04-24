package com.example.ansiblehook.webhook;

import com.example.ansiblehook.AnsiblehookProperties;
import com.example.ansiblehook.WebhookProperties;
import com.example.ansiblehook.ansible.AnsibleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebhookControllerTest {

    private static final String SECRET = "test-secret";

    private WebhookController controller;

    @BeforeEach
    void setUp() {
        AnsiblehookProperties props = mock(AnsiblehookProperties.class);
        when(props.webhooks()).thenReturn(Map.of("test",
                new WebhookProperties(SECRET, "/tmp", "hosts", "site.yml",
                        null, null, null, null)));

        controller = new WebhookController(props, mock(AnsibleService.class));
    }

    // ------------------------------------------------------------
    // HMAC TESTS (HMAC(secret, timestamp))
    // ------------------------------------------------------------

    @Test
    void validHmac_acceptsCorrectSignature() {
        String ts = "1713980000";
        String sig = hmac(SECRET, ts);
        assertThat(controller.validHmac(SECRET, ts.getBytes(StandardCharsets.UTF_8), sig)).isTrue();
    }

    @Test
    void validHmac_rejectsWrongSecret() {
        String ts = "1713980000";
        String sig = hmac(SECRET, ts);
        assertThat(controller.validHmac("wrong-secret", ts.getBytes(StandardCharsets.UTF_8), sig)).isFalse();
    }

    @Test
    void validHmac_rejectsTamperedTimestamp() {
        String ts = "1713980000";
        String sig = hmac(SECRET, ts);
        assertThat(controller.validHmac(SECRET, "1713980001".getBytes(StandardCharsets.UTF_8), sig)).isFalse();
    }

    @Test
    void validHmac_rejectsNullSignature() {
        String ts = "1713980000";
        assertThat(controller.validHmac(SECRET, ts.getBytes(StandardCharsets.UTF_8), null)).isFalse();
    }

    @Test
    void validHmac_rejectsMissingPrefix() {
        String ts = "1713980000";
        String raw = hmac(SECRET, ts).substring("sha256=".length());
        assertThat(controller.validHmac(SECRET, ts.getBytes(StandardCharsets.UTF_8), raw)).isFalse();
    }

    // ------------------------------------------------------------
    // TIMESTAMP TESTS
    // ------------------------------------------------------------

    @Test
    void validTimestamp_acceptsFreshTimestamp() {
        long now = Instant.now().getEpochSecond();
        assertThat(controller.validTimestamp(String.valueOf(now))).isTrue();
    }

    @Test
    void validTimestamp_rejectsOldTimestamp() {
        long old = Instant.now().minus(Duration.ofMinutes(10)).getEpochSecond();
        assertThat(controller.validTimestamp(String.valueOf(old))).isFalse();
    }

    @Test
    void validTimestamp_rejectsInvalidFormat() {
        assertThat(controller.validTimestamp("not-a-number")).isFalse();
    }

    // ------------------------------------------------------------
    // Hilfsmethode: erzeugt dieselbe Signatur wie der Controller
    // ------------------------------------------------------------
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
