package com.example.ansiblehook.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.ansiblehook.ansible.AnsibleService;
import com.example.ansiblehook.ansible.PlaybookAlreadyRunningException;
import com.example.ansiblehook.ansible.PlaybookFailedException;
import com.example.ansiblehook.ott.TokenService;
import com.example.ansiblehook.AnsiblehookProperties;
import com.example.ansiblehook.WebhookProperties;

import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@RestController
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final Map<String, WebhookProperties> webhooks;
    private final AnsibleService ansibleService;
    private final TokenService tokenService;

    public WebhookController(AnsiblehookProperties properties, AnsibleService ansibleService,
            TokenService tokenService) {
        this.webhooks = properties.webhooks();
        this.ansibleService = ansibleService;
        this.tokenService = tokenService;
    }

    @GetMapping("/webhook/{id}/status")
    public ResponseEntity<Map<String, Object>> status(
            @PathVariable String id,
            @RequestHeader(name = "X-Webhook-Timestamp", required = false) String timestamp,
            @RequestHeader(name = "X-Webhook-Signature", required = false) String signature,
            @RequestHeader(name = "X-Webhook-Token", required = false) String token) {

        WebhookProperties props = webhooks.get(id);
        if (props == null) {
            log.warn("Status check for unknown webhook '{}'", id);
            return ResponseEntity.notFound().build();
        }

        if (authenticate(id, props, new WebhookCredentials(timestamp, signature, token)) != null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        boolean running = ansibleService.isRunning(id);
        return ResponseEntity.ok(Map.of("id", id, "running", running));
    }

    @PostMapping("/webhook/{id}")
    public Mono<ResponseEntity<String>> trigger(
            @PathVariable String id,
            @RequestHeader(name = "X-Webhook-Timestamp", required = false) String timestamp,
            @RequestHeader(name = "X-Webhook-Signature", required = false) String signature,
            @RequestHeader(name = "X-Webhook-Token", required = false) String token) {

        WebhookProperties props = webhooks.get(id);
        if (props == null) {
            log.warn("Webhook '{}' not found", id);
            return Mono.just(ResponseEntity.notFound().build());
        }

        String authError = authenticate(id, props, new WebhookCredentials(timestamp, signature, token));
        if (authError != null) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(authError));
        }

        return ansibleService.execute(id, props)
                .map(ResponseEntity::ok)
                .onErrorResume(PlaybookAlreadyRunningException.class,
                        ex -> Mono.just(ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage())))
                .onErrorResume(PlaybookFailedException.class,
                        ex -> Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                .body("Playbook failed")))
                .onErrorResume(ex -> {
                    log.error("Unexpected error executing webhook '{}'", id, ex);
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body("Internal error"));
                });
    }

    @PostMapping("/webhook/{id}/token")
    public ResponseEntity<Map<String, Object>> createToken(
            @PathVariable String id,
            @RequestHeader("X-Webhook-Timestamp") String timestamp,
            @RequestHeader("X-Webhook-Signature") String signature,
            @RequestParam(name = "ttl", defaultValue = "3600") long ttlSeconds,
            @RequestParam(name = "not_before", required = false) String notBeforeParam) {

        WebhookProperties props = webhooks.get(id);
        if (props == null) {
            log.warn("Token request for unknown webhook '{}'", id);
            return ResponseEntity.notFound().build();
        }

        if (authenticate(id, props, new WebhookCredentials(timestamp, signature, null)) != null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Instant notBefore;
        try {
            notBefore = notBeforeParam != null ? Instant.parse(notBeforeParam) : Instant.now();
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }

        Duration ttl = Duration.ofSeconds(Math.max(1, Math.min(ttlSeconds, 86400)));
        TokenService.CreatedToken created = tokenService.create(id, notBefore, ttl);

        return ResponseEntity.ok(Map.of(
                "token", created.token(),
                "webhook_id", id,
                "not_before", created.notBefore().toString(),
                "expires_at", created.expiresAt().toString()));
    }

    /**
     * Authenticates via one-time token if present, otherwise via HMAC signature.
     * Returns null when authenticated, or an error message describing the failure.
     */
    private String authenticate(String id, WebhookProperties props, WebhookCredentials credentials) {
        if (credentials.token() != null) {
            if (!tokenService.validateAndConsume(id, credentials.token())) {
                log.warn("Webhook '{}' rejected: invalid or expired one-time token", id);
                return "Invalid or expired token";
            }
            return null;
        }

        if (credentials.timestamp() == null || credentials.signature() == null) {
            return "Provide either X-Webhook-Token or X-Webhook-Timestamp + X-Webhook-Signature";
        }
        if (!tokenService.validTimestamp(credentials.timestamp())) {
            log.warn("Webhook '{}' rejected: invalid timestamp", id);
            return "Invalid timestamp";
        }
        String message = credentials.timestamp() + "." + id;
        if (!tokenService.validHmac(props.secret(), message.getBytes(StandardCharsets.UTF_8), credentials.signature())) {
            log.warn("Webhook '{}' rejected: invalid signature", id);
            return "Invalid signature";
        }
        return null;
    }
}
