package com.rhizodelta.consensus.domain.exception;

public class DagIntegrityViolationException extends RuntimeException {
    public DagIntegrityViolationException(String message) {
        super(message);
    }
}
