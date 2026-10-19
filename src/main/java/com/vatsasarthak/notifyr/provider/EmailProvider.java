package com.vatsasarthak.notifyr.provider;

public interface EmailProvider {

    void send(String to, String subject, String body) throws Exception;

    // true when the provider only pretends to deliver (development mock mode)
    default boolean isSimulated() {
        return false;
    }
}
