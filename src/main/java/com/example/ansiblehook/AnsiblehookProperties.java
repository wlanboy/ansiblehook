package com.example.ansiblehook;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.Map;

@ConfigurationProperties(prefix = "ansiblehook")
public record AnsiblehookProperties(Map<String, WebhookProperties> webhooks) {}
