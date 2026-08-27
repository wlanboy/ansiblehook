package com.example.ansiblehook;

import java.util.ArrayList;
import java.util.List;

public record WebhookProperties(
        String secret,
        String folder,
        String hosts,
        String playbook,
        String limit,
        String tags,
        String extraVars,
        String vaultPasswordFile
) {

    public List<String> toCommand() {
        List<String> cmd = new ArrayList<>(List.of(
                "ansible-playbook", "-i", hosts, playbook
        ));
        if (limit != null && !limit.isBlank()) {
            cmd.addAll(List.of("--limit", limit));
        }
        if (tags != null && !tags.isBlank()) {
            cmd.addAll(List.of("--tags", tags));
        }
        if (extraVars != null && !extraVars.isBlank()) {
            cmd.addAll(List.of("--extra-vars", extraVars));
        }
        if (vaultPasswordFile != null && !vaultPasswordFile.isBlank()) {
            cmd.addAll(List.of("--vault-password-file", vaultPasswordFile));
        }
        return cmd;
    }
}
