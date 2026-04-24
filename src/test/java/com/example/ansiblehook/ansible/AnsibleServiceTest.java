package com.example.ansiblehook.ansible;

import com.example.ansiblehook.WebhookProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.test.StepVerifier;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// ProcessBuilder und Process werden über newProcessBuilder() gemockt (package-private test seam).
// Kein Spring-Kontext nötig — reine Unit-Tests der Service-Logik.
class AnsibleServiceTest {

    private AnsibleService service;
    private ProcessBuilder pb;
    private Process process;

    @BeforeEach
    void setUp() throws Exception {
        process = mock(Process.class);
        pb = mock(ProcessBuilder.class);
        when(pb.start()).thenReturn(process);

        service = spy(new AnsibleService());
        doReturn(pb).when(service).newProcessBuilder(any());
    }

    private void givenOutput(String output, int exitCode) throws Exception {
        when(process.getInputStream())
                .thenReturn(new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)));
        when(process.waitFor()).thenReturn(exitCode);
    }

    private WebhookProperties props(String folder, String limit, String tags) {
        return new WebhookProperties("test-secret", folder, "inventory", "site.yml", limit, tags, null, null);
    }

    // Ansible-Output landet unverändert als Mono-Ergebnis.
    @Test
    void execute_returnsOutput_onSuccess() throws Exception {
        givenOutput("playbook output", 0);

        StepVerifier.create(service.execute("play", props("/tmp", null, null)))
                .expectNext("playbook output")
                .verifyComplete();
    }

    // Exit-Code != 0 → PlaybookFailedException, die den Output des Prozesses mitführt.
    @Test
    void execute_throwsPlaybookFailedException_onNonZeroExit() throws Exception {
        givenOutput("error output", 2);

        StepVerifier.create(service.execute("play", props("/tmp", null, null)))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PlaybookFailedException.class);
                    assertThat(((PlaybookFailedException) e).getOutput()).isEqualTo("error output");
                })
                .verify();
    }

    // running.add() ist synchron — der Slot ist bereits beim zweiten execute()-Aufruf belegt,
    // noch bevor der erste Mono subscribed wird.
    @Test
    void execute_throwsAlreadyRunning_whenSameNameCalledTwice() {
        service.execute("play", props("/tmp", null, null));

        StepVerifier.create(service.execute("play", props("/tmp", null, null)))
                .expectError(PlaybookAlreadyRunningException.class)
                .verify();
    }

    // doFinally gibt den Namen nach Abschluss frei — der gleiche Webhook kann danach erneut ausgelöst werden.
    @Test
    void execute_allowsReuse_afterCompletion() throws Exception {
        givenOutput("", 0);

        StepVerifier.create(service.execute("play", props("/tmp", null, null)))
                .expectNextCount(1)
                .verifyComplete();

        StepVerifier.create(service.execute("play", props("/tmp", null, null)))
                .expectNextCount(1)
                .verifyComplete();
    }

    // Gesetzte limit- und tags-Werte erscheinen als --limit und --tags im Prozess-Kommando.
    @Test
    @SuppressWarnings("unchecked")
    void buildCommand_includesLimitAndTags() throws Exception {
        givenOutput("", 0);
        ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
        doReturn(pb).when(service).newProcessBuilder(cmdCaptor.capture());

        StepVerifier.create(service.execute("play",
                new WebhookProperties("test-secret", "/tmp", "inventory", "site.yml", "web", "deploy", null, null)))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(cmdCaptor.getValue()).containsExactly(
                "ansible-playbook", "-i", "inventory", "site.yml", "--limit", "web", "--tags", "deploy");
    }

    // Leere (blank) oder null-Werte für limit/tags erzeugen keine zusätzlichen Argumente.
    @Test
    @SuppressWarnings("unchecked")
    void buildCommand_omitsLimitAndTags_whenBlankOrNull() throws Exception {
        givenOutput("", 0);
        ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
        doReturn(pb).when(service).newProcessBuilder(cmdCaptor.capture());

        StepVerifier.create(service.execute("play",
                new WebhookProperties("test-secret", "/tmp", "inventory", "site.yml", " ", null, null, null)))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(cmdCaptor.getValue())
                .containsExactly("ansible-playbook", "-i", "inventory", "site.yml")
                .doesNotContain("--limit", "--tags", "--extra-vars");
    }

    // "~" allein wird zu user.home aufgelöst.
    @Test
    void resolveFolder_expandsTilde() throws Exception {
        givenOutput("", 0);

        StepVerifier.create(service.execute("play", props("~", null, null)))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<File> dirCaptor = ArgumentCaptor.forClass(File.class);
        verify(pb).directory(dirCaptor.capture());
        assertThat(dirCaptor.getValue().getAbsolutePath())
                .isEqualTo(System.getProperty("user.home"));
    }

    // "~/pfad" wird zu user.home/pfad aufgelöst.
    @Test
    void resolveFolder_expandsTildeSlash() throws Exception {
        givenOutput("", 0);

        StepVerifier.create(service.execute("play", props("~/projects", null, null)))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<File> dirCaptor = ArgumentCaptor.forClass(File.class);
        verify(pb).directory(dirCaptor.capture());
        assertThat(dirCaptor.getValue().getAbsolutePath())
                .isEqualTo(System.getProperty("user.home") + "/projects");
    }

    // Absolute Pfade werden unverändert als Arbeitsverzeichnis übergeben.
    @Test
    void resolveFolder_usesAbsolutePathUnchanged() throws Exception {
        givenOutput("", 0);

        StepVerifier.create(service.execute("play", props("/opt/ansible", null, null)))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<File> dirCaptor = ArgumentCaptor.forClass(File.class);
        verify(pb).directory(dirCaptor.capture());
        assertThat(dirCaptor.getValue().getAbsolutePath()).isEqualTo("/opt/ansible");
    }

    // isRunning() gibt true zurück solange der Mono noch nicht abgeschlossen ist.
    @Test
    void isRunning_returnsTrueWhileExecuting() {
        assertThat(service.isRunning("play")).isFalse();
        service.execute("play", props("/tmp", null, null)); // nicht subscribed → Slot trotzdem belegt
        assertThat(service.isRunning("play")).isTrue();
    }

    // Nach Abschluss des Mono ist der Slot wieder frei.
    @Test
    void isRunning_returnsFalse_afterCompletion() throws Exception {
        givenOutput("", 0);
        assertThat(service.isRunning("play")).isFalse();
        StepVerifier.create(service.execute("play", props("/tmp", null, null)))
                .expectNextCount(1)
                .verifyComplete();
        assertThat(service.isRunning("play")).isFalse();
    }

    // --extra-vars erscheint am Ende des Kommandos wenn gesetzt.
    @Test
    @SuppressWarnings("unchecked")
    void buildCommand_includesExtraVars() throws Exception {
        givenOutput("", 0);
        ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
        doReturn(pb).when(service).newProcessBuilder(cmdCaptor.capture());

        StepVerifier.create(service.execute("play",
                new WebhookProperties("test-secret", "/tmp", "inventory", "site.yml", null, null, "env=prod", null)))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(cmdCaptor.getValue()).containsExactly(
                "ansible-playbook", "-i", "inventory", "site.yml", "--extra-vars", "env=prod");
    }

    // --vault-password-file erscheint im Kommando wenn gesetzt.
    @Test
    @SuppressWarnings("unchecked")
    void buildCommand_includesVaultPasswordFile() throws Exception {
        givenOutput("", 0);
        ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
        doReturn(pb).when(service).newProcessBuilder(cmdCaptor.capture());

        StepVerifier.create(service.execute("play",
                new WebhookProperties("test-secret", "/tmp", "inventory", "site.yml", null, null, null, "/run/secrets/vault_pass")))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(cmdCaptor.getValue()).containsExactly(
                "ansible-playbook", "-i", "inventory", "site.yml", "--vault-password-file", "/run/secrets/vault_pass");
    }

    // redirectErrorStream(true) muss gesetzt sein, damit Stderr im Fehlerfall im Response-Body landet.
    @Test
    void execute_mergesStderr_intoStdout() throws Exception {
        givenOutput("", 0);

        StepVerifier.create(service.execute("play", props("/tmp", null, null)))
                .expectNextCount(1)
                .verifyComplete();

        verify(pb).redirectErrorStream(true);
    }

    // Wenn ansible-playbook nicht gefunden wird, wirft pb.start() eine IOException —
    // diese muss als Mono.error() propagiert werden, nicht als unkontrollierte Exception.
    @Test
    void execute_propagatesError_whenProcessFailsToStart() throws Exception {
        when(pb.start()).thenThrow(new java.io.IOException("ansible-playbook not found"));

        StepVerifier.create(service.execute("play", props("/tmp", null, null)))
                .expectError(java.io.IOException.class)
                .verify();
    }
}
