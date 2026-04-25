package com.example.ansiblehook.ott;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;

@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);
    public static final Duration DEFAULT_TTL = Duration.ofHours(1);

    private static final String HMAC_PREFIX = "sha256=";
    private static final Duration TIMESTAMP_TOLERANCE = Duration.ofMinutes(5);

    private record TokenEntry(String webhookId, Instant notBefore, Instant expiresAt) {}

    public record CreatedToken(String token, Instant notBefore, Instant expiresAt) {}

    private final ConcurrentHashMap<String, TokenEntry> tokens = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();

    public CreatedToken create(String webhookId, Instant notBefore, Duration ttl) {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        Instant expiresAt = notBefore.plus(ttl);
        tokens.put(token, new TokenEntry(webhookId, notBefore, expiresAt));
        log.info("One-time token created for webhook '{}', valid {} – {}", webhookId, notBefore, expiresAt);
        return new CreatedToken(token, notBefore, expiresAt);
    }

    public boolean validateAndConsume(String webhookId, String token) {
        Instant now = Instant.now();
        TokenEntry entry = tokens.get(token);
        if (entry == null) {
            return false;
        }
        if (!entry.webhookId().equals(webhookId)) {
            log.warn("Token presented for webhook '{}' but was issued for '{}'", webhookId, entry.webhookId());
            tokens.remove(token);
            return false;
        }
        if (now.isBefore(entry.notBefore())) {
            log.warn("Token for webhook '{}' not yet valid (valid from {})", webhookId, entry.notBefore());
            return false;
        }
        if (now.isAfter(entry.expiresAt())) {
            log.warn("Expired one-time token used for webhook '{}'", webhookId);
            tokens.remove(token);
            return false;
        }
        tokens.remove(token);
        return true;
    }

    public boolean validHmac(String secret, byte[] message, String signature) {
        if (signature == null || !signature.startsWith(HMAC_PREFIX))
            return false;

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(message);

            String expectedHex = HMAC_PREFIX + bytesToHex(expected);

            return MessageDigest.isEqual(
                    expectedHex.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));

        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("HMAC validation error (secret not shown)", e);
            return false;
        }
    }

    public boolean validTimestamp(String ts) {
        try {
            long epoch = Long.parseLong(ts);
            Instant timestamp = Instant.ofEpochSecond(epoch);
            Instant now = Instant.now();

            Duration diff = Duration.between(timestamp, now).abs();
            return diff.compareTo(TIMESTAMP_TOLERANCE) <= 0;

        } catch (Exception e) {
            return false;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes)
            sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @Scheduled(fixedDelay = 60_000)
    public void cleanupExpired() {
        Instant now = Instant.now();
        int removed = 0;
        var it = tokens.entrySet().iterator();
        while (it.hasNext()) {
            if (now.isAfter(it.next().getValue().expiresAt())) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("Cleaned up {} expired one-time token(s)", removed);
        }
    }
}
