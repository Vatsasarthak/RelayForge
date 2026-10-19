package com.vatsasarthak.notifyr.exception;

public class RecipientNotFoundException extends RuntimeException {
    public RecipientNotFoundException(Long id) {
        super("Recipient not found with id: " + id);
    }
}
