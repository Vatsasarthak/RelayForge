package com.vatsasarthak.notifyr.dto;

import com.vatsasarthak.notifyr.entity.NotificationChannel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CampaignRequest {

    @NotBlank(message = "Campaign name is required")
    @Size(max = 255, message = "name must be at most 255 characters")
    private String name;

    @Size(max = 255, message = "description must be at most 255 characters")
    private String description;

    @NotNull(message = "channel is required")
    private NotificationChannel channel;

    @NotNull(message = "templateId is required")
    private Long templateId;

    @Size(max = 10000, message = "at most 10000 recipientIds")
    private List<@NotNull Long> recipientIds;

    @Size(max = 35, message = "audienceLanguage must be at most 35 characters")
    private String audienceLanguage;

    @Size(max = 50, message = "at most 50 audienceTags")
    private List<@NotBlank @Size(max = 255) String> audienceTags;

    @Size(max = 100, message = "at most 100 templateVariables")
    private Map<@NotBlank @Size(max = 255) String, @Size(max = 255) String> templateVariables;

    private LocalDateTime scheduledTime;
}
