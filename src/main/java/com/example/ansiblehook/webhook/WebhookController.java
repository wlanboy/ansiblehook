package com.example.ansiblehook.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.example.ansiblehook.ansible.AnsibleService;
import com.example.ansiblehook.ansible.PlaybookAlreadyRunningException;
import com.example.ansiblehook.ansible.PlaybookFailedException;
import com.example.ansiblehook.AnsiblehookProperties;
import com.example.ansiblehook.WebhookProperties;

import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private static final String HMAC_PREFIX = "sha256=";
    private static final Duration TIMESTAMP_TOLERANCE = Duration.ofMinutes(5);

    private final Map<String, WebhookProperties> webhooks;
    private final AnsibleService ansibleService;

    public WebhookController(AnsiblehookProperties properties, AnsibleService ansibleService) {
        this.webhooks = properties.webhooks();
        this.ansibleService = ansibleService;
    }

    @GetMapping("/webhook/{id}/status")
    public ResponseEntity<Map<String, Object>> status(
            @PathVariable String id,
            @RequestHeader("X-Webhook-Timestamp") String timestamp,
            @RequestHeader("X-Webhook-Signature") String signature) {

        WebhookProperties props = webhooks.get(id);
        if (props == null) {
            log.warn("Status check for unknown webhook '{}'", id);
            return ResponseEntity.notFound().build();
        }

        if (!validTimestamp(timestamp)) {
            log.warn("Status check for webhook '{}' rejected: invalid timestamp", id);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (!validHmac(props.secret(), timestamp.getBytes(StandardCharsets.UTF_8), signature)) {
            log.warn("Status check for webhook '{}' rejected: invalid signature", id);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        boolean running = ansibleService.isRunning(id);
        return ResponseEntity.ok(Map.of("id", id, "running", running));
    }

    @PostMapping("/webhook/{id}")
    public Mono<ResponseEntity<String>> trigger(
            @PathVariable String id,
            @RequestHeader("X-Webhook-Timestamp") String timestamp,
            @RequestHeader("X-Webhook-Signature") String signature) {

        WebhookProperties props = webhooks.get(id);
        if (props == null) {
            log.warn("Webhook '{}' not found", id);
            return Mono.just(ResponseEntity.notFound().build());
        }

        if (!validTimestamp(timestamp)) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid timestamp"));
        }

        if (!validHmac(props.secret(), timestamp.getBytes(StandardCharsets.UTF_8), signature)) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature"));
        }

        return ansibleService.execute(id, props)
                .map(ResponseEntity::ok)
                .onErrorResume(PlaybookAlreadyRunningException.class,
                        ex -> Mono.just(ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage())))
                .onErrorResume(PlaybookFailedException.class,
                        ex -> Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                .body("Playbook failed")));
    }

    // ---------------------------------------------------------
    // HMAC VALIDATION
    // ---------------------------------------------------------
    boolean validHmac(String secret, byte[] message, String signature) {
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

    // ---------------------------------------------------------
    // TIMESTAMP VALIDATION
    // ---------------------------------------------------------
    boolean validTimestamp(String ts) {
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

    // ---------------------------------------------------------
    // HEX ENCODING
    // ---------------------------------------------------------
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes)
            sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
