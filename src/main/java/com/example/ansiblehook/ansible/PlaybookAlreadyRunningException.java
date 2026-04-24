package com.example.ansiblehook.ansible;

public class PlaybookAlreadyRunningException extends RuntimeException {

    public PlaybookAlreadyRunningException(String name) {
        super("Playbook '" + name + "' is already running");
    }
}
