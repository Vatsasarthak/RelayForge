package com.vatsasarthak.notifyr.dto;

import com.vatsasarthak.notifyr.entity.CampaignStatus;
import com.vatsasarthak.notifyr.entity.NotificationChannel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CampaignSummary {

    private Long id;
    private String name;
    private String description;
    private NotificationChannel channel;
    private Long templateId;
    private String audienceLanguage;
    private CampaignStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime scheduledTime;
}
