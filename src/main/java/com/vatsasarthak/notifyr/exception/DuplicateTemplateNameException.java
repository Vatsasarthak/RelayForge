package com.vatsasarthak.notifyr.exception;

public class DuplicateTemplateNameException extends RuntimeException {
    public DuplicateTemplateNameException(String name) {
        super("Template with name '" + name + "' already exists");
    }
}
