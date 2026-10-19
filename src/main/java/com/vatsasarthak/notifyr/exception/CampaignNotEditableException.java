package com.vatsasarthak.notifyr.exception;

import com.vatsasarthak.notifyr.entity.CampaignStatus;

public class CampaignNotEditableException extends RuntimeException {
    public CampaignNotEditableException(Long id, CampaignStatus currentStatus) {
        super("Campaign " + id + " cannot be modified - current status is " + currentStatus +
                " (only DRAFT or SCHEDULED campaigns can be changed)");
    }
}
