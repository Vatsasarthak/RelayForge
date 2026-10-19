package com.vatsasarthak.notifyr.provider;

// A delivery that can never succeed by retrying (missing address, provider not configured, ...).
// The worker marks the notification FAILED immediately instead of scheduling retries.
public class PermanentDeliveryException extends RuntimeException {
    public PermanentDeliveryException(String message) {
        super(message);
    }
}
