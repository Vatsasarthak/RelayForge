package com.vatsasarthak.notifyr.exception;

public class DeadLetterNotReplayableException extends RuntimeException {
    public DeadLetterNotReplayableException(Long id, String reason) {
        super("Dead letter " + id + " cannot be replayed: " + reason);
    }
}
