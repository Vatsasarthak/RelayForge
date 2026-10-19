package com.vatsasarthak.notifyr.exception;

public class InvalidCredentialsException extends RuntimeException{
    public InvalidCredentialsException() {
        super("Invalid username or password");
    }
}
