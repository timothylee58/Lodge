package com.claimdesk.reporting.consumer;

/** A message that can never be processed (bad JSON, missing fields). Never retried: straight to the DLT. */
public class PoisonEventException extends RuntimeException {
    public PoisonEventException(String message, Throwable cause) { super(message, cause); }
    public PoisonEventException(String message) { super(message); }
}
