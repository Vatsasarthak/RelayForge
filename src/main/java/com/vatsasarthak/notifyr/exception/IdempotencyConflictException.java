package com.vatsasarthak.notifyr.exception;

public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String idempotencyKey) {
        super("Idempotency-Key '" + idempotencyKey + "' was already used for a different request");
    }
}
