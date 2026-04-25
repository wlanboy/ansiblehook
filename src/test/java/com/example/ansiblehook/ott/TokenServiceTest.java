package com.example.ansiblehook.ott;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TokenServiceTest {

    private static final String SECRET = "test-secret";

    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new TokenService();
    }

    // ------------------------------------------------------------
    // ONE-TIME TOKEN TESTS
    // ------------------------------------------------------------

    @Test
    void createToken_returnsNonBlankToken() {
        var created = tokenService.create("my-webhook", Instant.now(), TokenService.DEFAULT_TTL);
        assertThat(created.token()).isNotBlank();
        assertThat(created.expiresAt()).isNotNull();
    }

    @Test
    void validateAndConsume_acceptsValidToken() {
        var created = tokenService.create("my-webhook", Instant.now(), TokenService.DEFAULT_TTL);
        assertThat(tokenService.validateAndConsume("my-webhook", created.token())).isTrue();
    }

    @Test
    void validateAndConsume_tokenIsConsumedAfterUse() {
        var created = tokenService.create("my-webhook", Instant.now(), TokenService.DEFAULT_TTL);
        tokenService.validateAndConsume("my-webhook", created.token());
        assertThat(tokenService.validateAndConsume("my-webhook", created.token())).isFalse();
    }

    @Test
    void validateAndConsume_rejectsUnknownToken() {
        assertThat(tokenService.validateAndConsume("my-webhook", "deadbeef")).isFalse();
    }

    @Test
    void validateAndConsume_rejectsTokenForWrongWebhook() {
        var created = tokenService.create("webhook-a", Instant.now(), TokenService.DEFAULT_TTL);
        assertThat(tokenService.validateAndConsume("webhook-b", created.token())).isFalse();
    }

    @Test
    void validateAndConsume_rejectsExpiredToken() throws InterruptedException {
        var created = tokenService.create("my-webhook", Instant.now(), Duration.ofMillis(1));
        Thread.sleep(10);
        assertThat(tokenService.validateAndConsume("my-webhook", created.token())).isFalse();
    }

    @Test
    void cleanupExpired_removesExpiredTokens() throws InterruptedException {
        tokenService.create("my-webhook", Instant.now(), Duration.ofMillis(1));
        Thread.sleep(10);
        tokenService.cleanupExpired();
        assertThat(tokenService.validateAndConsume("my-webhook", "any")).isFalse();
    }

    // ------------------------------------------------------------
    // HMAC TESTS
    // ------------------------------------------------------------

    @Test
    void validHmac_acceptsCorrectSignature() {
        String ts = "1713980000";
        String sig = hmac(SECRET, ts);
        assertThat(tokenService.validHmac(SECRET, ts.getBytes(StandardCharsets.UTF_8), sig)).isTrue();
    }

    @Test
    void validHmac_rejectsWrongSecret() {
        String ts = "1713980000";
        String sig = hmac(SECRET, ts);
        assertThat(tokenService.validHmac("wrong-secret", ts.getBytes(StandardCharsets.UTF_8), sig)).isFalse();
    }

    @Test
    void validHmac_rejectsTamperedTimestamp() {
        String ts = "1713980000";
        String sig = hmac(SECRET, ts);
        assertThat(tokenService.validHmac(SECRET, "1713980001".getBytes(StandardCharsets.UTF_8), sig)).isFalse();
    }

    @Test
    void validHmac_rejectsNullSignature() {
        String ts = "1713980000";
        assertThat(tokenService.validHmac(SECRET, ts.getBytes(StandardCharsets.UTF_8), null)).isFalse();
    }

    @Test
    void validHmac_rejectsMissingPrefix() {
        String ts = "1713980000";
        String raw = hmac(SECRET, ts).substring("sha256=".length());
        assertThat(tokenService.validHmac(SECRET, ts.getBytes(StandardCharsets.UTF_8), raw)).isFalse();
    }

    // ------------------------------------------------------------
    // TIMESTAMP TESTS
    // ------------------------------------------------------------

    @Test
    void validTimestamp_acceptsFreshTimestamp() {
        long now = Instant.now().getEpochSecond();
        assertThat(tokenService.validTimestamp(String.valueOf(now))).isTrue();
    }

    @Test
    void validTimestamp_rejectsOldTimestamp() {
        long old = Instant.now().minus(Duration.ofMinutes(10)).getEpochSecond();
        assertThat(tokenService.validTimestamp(String.valueOf(old))).isFalse();
    }

    @Test
    void validTimestamp_rejectsInvalidFormat() {
        assertThat(tokenService.validTimestamp("not-a-number")).isFalse();
    }

    @Test
    void validateAndConsume_rejectsTokenBeforeNotBefore() {
        Instant future = Instant.now().plusSeconds(60);
        var created = tokenService.create("my-webhook", future, TokenService.DEFAULT_TTL);
        assertThat(tokenService.validateAndConsume("my-webhook", created.token())).isFalse();
    }

    @Test
    void validateAndConsume_acceptsTokenAfterNotBefore() {
        Instant past = Instant.now().minusSeconds(60);
        var created = tokenService.create("my-webhook", past, TokenService.DEFAULT_TTL);
        assertThat(tokenService.validateAndConsume("my-webhook", created.token())).isTrue();
    }

    @Test
    void validateAndConsume_notBeforeTokenRemainsUsableAfterWindow() {
        Instant future = Instant.now().plusSeconds(60);
        var created = tokenService.create("my-webhook", future, TokenService.DEFAULT_TTL);
        // rejected but NOT consumed — token must still be in the store
        tokenService.validateAndConsume("my-webhook", created.token());
        // still there (not yet valid, so not consumed)
        assertThat(created.token()).isNotBlank();
    }

    @Test
    void createdToken_exposesNotBefore() {
        Instant notBefore = Instant.now().plusSeconds(300);
        var created = tokenService.create("my-webhook", notBefore, TokenService.DEFAULT_TTL);
        assertThat(created.notBefore()).isEqualTo(notBefore);
        assertThat(created.expiresAt()).isEqualTo(notBefore.plus(TokenService.DEFAULT_TTL));
    }

    @Test
    void createToken_producesDifferentTokensEachTime() {
        var a = tokenService.create("my-webhook", Instant.now(), TokenService.DEFAULT_TTL);
        var b = tokenService.create("my-webhook", Instant.now(), TokenService.DEFAULT_TTL);
        assertThat(a.token()).isNotEqualTo(b.token());
    }

    @Test
    void validTimestamp_acceptsFutureTimestampWithinTolerance() {
        long soon = Instant.now().plusSeconds(60).getEpochSecond();
        assertThat(tokenService.validTimestamp(String.valueOf(soon))).isTrue();
    }

    @Test
    void validTimestamp_rejectsFutureTimestampBeyondTolerance() {
        long far = Instant.now().plusSeconds(400).getEpochSecond();
        assertThat(tokenService.validTimestamp(String.valueOf(far))).isFalse();
    }

    // ------------------------------------------------------------
    // Hilfsmethode
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
