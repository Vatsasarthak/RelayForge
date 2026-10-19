package com.vatsasarthak.notifyr.dto;

import com.vatsasarthak.notifyr.entity.NotificationChannel;
import com.vatsasarthak.notifyr.entity.NotificationStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationResponse {

    private Long id;
    private String recipientEmail;
    private NotificationStatus status;
    private String subject;
    private String failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private NotificationChannel channel;
    private Integer retryCount;
    private Long campaignId;
    private LocalDateTime sentAt;
}
