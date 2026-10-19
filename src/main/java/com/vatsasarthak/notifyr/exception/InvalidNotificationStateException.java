package com.vatsasarthak.notifyr.exception;

import com.vatsasarthak.notifyr.entity.NotificationStatus;

public class InvalidNotificationStateException extends RuntimeException {
    public InvalidNotificationStateException(Long id, NotificationStatus currentStatus, String rule) {
        super("Notification " + id + " is " + currentStatus + ": " + rule);
    }
}
