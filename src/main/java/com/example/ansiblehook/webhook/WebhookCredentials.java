package com.example.ansiblehook.webhook;

public record WebhookCredentials(String timestamp, String signature, String token) {}
