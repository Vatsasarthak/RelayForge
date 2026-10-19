package com.vatsasarthak.notifyr.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationMessage {

    private Long notificationId;
    private String recipientEmail;
    private String subject;
    private String message;
    private int retryCount;
}
