package com.example.ansiblehook.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

@RestController
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final Map<String, WebhookProperties> webhooks;
    private final AnsibleService ansibleService;

    public WebhookController(AnsiblehookProperties properties, AnsibleService ansibleService) {
        this.webhooks = properties.webhooks();
        this.ansibleService = ansibleService;
    }

    @GetMapping("/webhook/{id}/status")
    public ResponseEntity<Map<String, Object>> status(
            @PathVariable String id,
            @RequestHeader(value = "X-Webhook-Secret", required = false) String secret) {
        WebhookProperties props = webhooks.get(id);
        if (props == null) {
            log.warn("Status check for unknown webhook '{}'", id);
            return ResponseEntity.notFound().build();
        }
        if (!validSecret(props.secret(), secret)) {
            log.warn("Status check for webhook '{}' rejected: invalid or missing secret", id);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        boolean running = ansibleService.isRunning(id);
        log.debug("Status check for webhook '{}': running={}", id, running);
        return ResponseEntity.ok(Map.of("id", id, "running", running));
    }

    @PostMapping("/webhook/{id}")
    public Mono<ResponseEntity<String>> trigger(
            @PathVariable String id,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody(required = false) Mono<String> bodyMono) {

        WebhookProperties props = webhooks.get(id);
        if (props == null) {
            log.warn("Webhook '{}' not found", id);
            return Mono.just(ResponseEntity.notFound().build());
        }

        Mono<String> safeBody = bodyMono != null ? bodyMono.defaultIfEmpty("") : Mono.just("");

        return safeBody.flatMap(payload -> {
            if (!validHmac(props.secret(), payload, signature)) {
                log.warn("Webhook '{}' rejected: invalid or missing HMAC signature", id);
                return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).<String>build());
            }
            log.info("Webhook '{}' triggered, payload size: {} bytes", id, payload.length());
            return ansibleService.execute(id, props)
                    .map(ResponseEntity::ok)
                    .onErrorResume(PlaybookAlreadyRunningException.class,
                            ex -> Mono.just(ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage())))
                    .onErrorResume(PlaybookFailedException.class,
                            ex -> Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ex.getOutput())));
        });
    }

    // package-private for testing
    boolean validHmac(String secret, String body, String signature) {
        if (signature == null || !signature.startsWith("sha256=")) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("HMAC validation error for webhook '{}'", secret.length(), e);
            return false;
        }
    }

    private boolean validSecret(String expected, String actual) {
        if (actual == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
