package com.vatsasarthak.notifyr.exception;

public class TemplateNotFoundException extends RuntimeException {
    public TemplateNotFoundException(Long id) {
        super("Template not found with id: " + id);
    }

    public TemplateNotFoundException(String name) {
        super("Template not found with name: " + name);
    }
}
