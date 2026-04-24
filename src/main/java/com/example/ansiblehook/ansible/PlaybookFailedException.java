package com.example.ansiblehook.ansible;

public class PlaybookFailedException extends RuntimeException {

    private final String output;

    public PlaybookFailedException(String output, int exitCode) {
        super("Playbook failed with exit code " + exitCode);
        this.output = output;
    }

    public String getOutput() {
        return output;
    }
}
