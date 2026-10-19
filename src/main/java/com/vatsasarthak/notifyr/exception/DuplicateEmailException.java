package com.vatsasarthak.notifyr.exception;

public class DuplicateEmailException extends RuntimeException {
    public DuplicateEmailException(String email) {
        super("A recipient with email '" + email + "' already exists");
    }
}
