package com.example.ansiblehook;

public record WebhookProperties(
        String secret,
        String folder,
        String hosts,
        String playbook,
        String limit,
        String tags
) {}
