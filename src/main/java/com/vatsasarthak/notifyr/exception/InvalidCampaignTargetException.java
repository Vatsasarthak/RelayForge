package com.vatsasarthak.notifyr.exception;

public class InvalidCampaignTargetException extends RuntimeException {
    public InvalidCampaignTargetException() {
        super("Campaign must specify either recipientIds or an audience filter (language/tags)");
    }
}
