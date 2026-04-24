package com.example.ansiblehook.webhook;

import com.example.ansiblehook.AnsiblehookProperties;
import com.example.ansiblehook.WebhookProperties;
import com.example.ansiblehook.ansible.AnsibleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Tests für die HMAC-Validierungslogik (package-private validHmac()).
// Kein Spring-Kontext nötig.
class WebhookControllerTest {

    private static final String SECRET = "test-secret";

    private WebhookController controller;

    @BeforeEach
    void setUp() {
        AnsiblehookProperties props = mock(AnsiblehookProperties.class);
        when(props.webhooks()).thenReturn(Map.of("test",
                new WebhookProperties(SECRET, "/tmp", "hosts", "site.yml", null, null, null, null)));
        controller = new WebhookController(props, mock(AnsibleService.class));
    }

    // Korrekte Signatur über leeren Body muss akzeptiert werden.
    @Test
    void validHmac_acceptsCorrectSignatureForEmptyBody() {
        String sig = hmac(SECRET, "");
        assertThat(controller.validHmac(SECRET, "", sig)).isTrue();
    }

    // Korrekte Signatur über JSON-Body muss akzeptiert werden.
    @Test
    void validHmac_acceptsCorrectSignatureForJsonBody() {
        String body = "{\"ref\":\"refs/heads/main\"}";
        assertThat(controller.validHmac(SECRET, body, hmac(SECRET, body))).isTrue();
    }

    // Falsches Secret ergibt anderen Hash → ablehnen.
    @Test
    void validHmac_rejectsWrongSecret() {
        String body = "payload";
        assertThat(controller.validHmac("wrong-secret", body, hmac(SECRET, body))).isFalse();
    }

    // Manipulierter Body ergibt anderen Hash → ablehnen.
    @Test
    void validHmac_rejectsTamperedBody() {
        String sig = hmac(SECRET, "original");
        assertThat(controller.validHmac(SECRET, "tampered", sig)).isFalse();
    }

    // Fehlendes Signatur-Header → ablehnen.
    @Test
    void validHmac_rejectsNullSignature() {
        assertThat(controller.validHmac(SECRET, "", null)).isFalse();
    }

    // Signatur ohne "sha256="-Präfix → ablehnen.
    @Test
    void validHmac_rejectsMissingPrefix() {
        String raw = hmac(SECRET, "").substring("sha256=".length());
        assertThat(controller.validHmac(SECRET, "", raw)).isFalse();
    }

    // Hilfsmethode: erzeugt dieselbe Signatur wie der Controller (für Test-Fixtures).
    static String hmac(String secret, String body) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + java.util.HexFormat.of().formatHex(
                    mac.doFinal(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
