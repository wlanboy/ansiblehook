package com.example.ansiblehook.ansible;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.ansiblehook.WebhookProperties;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AnsibleService {

    private static final Logger log = LoggerFactory.getLogger(AnsibleService.class);

    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public boolean isRunning(String name) {
        return running.contains(name);
    }

    public Mono<String> execute(String name, WebhookProperties props) {
        if (!running.add(name)) {
            log.warn("Playbook '{}' is already running, rejecting trigger", name);
            return Mono.error(new PlaybookAlreadyRunningException(name));
        }
        log.info("Starting playbook '{}': {}", name, props.playbook());
        return Mono.fromCallable(() -> runPlaybook(props))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(output -> log.info("Playbook '{}' finished successfully", name))
                .doOnError(ex -> log.error("Playbook '{}' failed: {}", name, ex.getMessage()))
                .doFinally(signal -> running.remove(name));
    }

    ProcessBuilder newProcessBuilder(List<String> cmd) {
        return new ProcessBuilder(cmd);
    }

    private String runPlaybook(WebhookProperties props) throws Exception {
        ProcessBuilder pb = newProcessBuilder(buildCommand(props));
        pb.directory(resolveFolder(props.folder()));
        pb.redirectErrorStream(true);

        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new PlaybookFailedException(output, exitCode);
        }
        return output;
    }

    private List<String> buildCommand(WebhookProperties props) {
        List<String> cmd = new ArrayList<>(List.of(
                "ansible-playbook", "-i", props.hosts(), props.playbook()
        ));
        if (props.limit() != null && !props.limit().isBlank()) {
            cmd.addAll(List.of("--limit", props.limit()));
        }
        if (props.tags() != null && !props.tags().isBlank()) {
            cmd.addAll(List.of("--tags", props.tags()));
        }
        return cmd;
    }

    private File resolveFolder(String folder) {
        String path = folder.equals("~") ? System.getProperty("user.home")
                : folder.startsWith("~/") ? System.getProperty("user.home") + folder.substring(1)
                : folder;
        return Path.of(path).toFile();
    }
}
