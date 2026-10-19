package com.vatsasarthak.notifyr.exception;

import com.vatsasarthak.notifyr.entity.NotificationChannel;

public class UnsupportedChannelException extends RuntimeException {
    public UnsupportedChannelException(NotificationChannel channel){
        super("Unsupported notification channel: " + channel);
    }
}
