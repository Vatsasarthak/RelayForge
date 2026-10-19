package com.vatsasarthak.notifyr.dto;

import java.time.LocalDateTime;

public record DeadLetterResponse(Long id, Long notificationId, String reason, String payload, String correlationId,
                                 LocalDateTime receivedAt, LocalDateTime replayedAt) {
}
