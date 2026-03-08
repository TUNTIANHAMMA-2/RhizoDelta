package com.rhizodelta.service;

public class DagIntegrityViolationException extends RuntimeException {
    public DagIntegrityViolationException(String message) {
        super(message);
    }
}
