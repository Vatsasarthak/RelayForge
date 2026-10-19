package com.vatsasarthak.notifyr.dto;

import com.vatsasarthak.notifyr.entity.CampaignStatus;
import com.vatsasarthak.notifyr.entity.NotificationStatus;

import java.util.Map;

public record CampaignStatsResponse(Long campaignId, CampaignStatus campaignStatus, long totalNotifications,
                                    Map<NotificationStatus, Long> notificationsByStatus) {
}
