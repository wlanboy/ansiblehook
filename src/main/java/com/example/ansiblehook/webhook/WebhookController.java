package com.example.ansiblehook.webhook;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.ansiblehook.ansible.AnsibleService;
import com.example.ansiblehook.ansible.PlaybookAlreadyRunningException;
import com.example.ansiblehook.ansible.PlaybookFailedException;
import com.example.ansiblehook.AnsiblehookProperties;
import com.example.ansiblehook.WebhookProperties;

import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
public class WebhookController {

    private final Map<String, WebhookProperties> webhooks;
    private final AnsibleService ansibleService;

    public WebhookController(AnsiblehookProperties properties, AnsibleService ansibleService) {
        this.webhooks = properties.webhooks();
        this.ansibleService = ansibleService;
    }

    @PostMapping("/webhook/{id}")
    public Mono<ResponseEntity<String>> trigger(@PathVariable String id) {
        return webhooks.entrySet().stream()
                .filter(e -> e.getValue().id().equals(id))
                .findFirst()
                .map(e -> ansibleService.execute(e.getKey(), e.getValue())
                        .map(output -> ResponseEntity.ok(output))
                        .onErrorResume(PlaybookAlreadyRunningException.class,
                                ex -> Mono.just(ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage())))
                        .onErrorResume(PlaybookFailedException.class,
                                ex -> Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ex.getOutput()))))
                .orElse(Mono.just(ResponseEntity.notFound().build()));
    }
}
