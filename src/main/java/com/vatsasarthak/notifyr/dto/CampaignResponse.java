package com.vatsasarthak.notifyr.dto;

import com.vatsasarthak.notifyr.entity.CampaignStatus;
import com.vatsasarthak.notifyr.entity.NotificationChannel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CampaignResponse {

    private Long id;
    private String name;
    private String description;
    private NotificationChannel channel;
    private Long templateId;
    private List<Long> recipientIds;
    private List<String> audienceTags;
    private String audienceLanguage;
    private Map<String, String> templateVariables;
    private CampaignStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime scheduledTime;
}
