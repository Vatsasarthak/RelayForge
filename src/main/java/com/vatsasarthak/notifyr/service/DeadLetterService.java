package com.vatsasarthak.notifyr.service;

import com.vatsasarthak.notifyr.dto.DeadLetterResponse;
import com.vatsasarthak.notifyr.dto.PageResponse;
import com.vatsasarthak.notifyr.entity.DeadLetter;
import com.vatsasarthak.notifyr.exception.DeadLetterNotReplayableException;
import com.vatsasarthak.notifyr.exception.NotificationNotFoundException;
import com.vatsasarthak.notifyr.repository.DeadLetterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class DeadLetterService {

    private final DeadLetterRepository deadLetterRepository;
    private final NotificationLifecycleService lifecycleService;

    @Transactional(readOnly = true)
    public PageResponse<DeadLetterResponse> list(Pageable pageable) {
        return PageResponse.of(deadLetterRepository.findAll(pageable), DeadLetterService::toResponse);
    }

    // Puts the dead-lettered notification back into delivery (FAILED -> QUEUED) and marks the record replayed.
    @Transactional
    public DeadLetterResponse replay(Long deadLetterId) {
        DeadLetter deadLetter = deadLetterRepository.findById(deadLetterId)
                .orElseThrow(() -> new NotificationNotFoundException("Dead letter " + deadLetterId + " not found"));
        if (deadLetter.getNotificationId() == null) {
            throw new DeadLetterNotReplayableException(deadLetterId, "it does not reference a notification");
        }
        if (deadLetter.getReplayedAt() != null) {
            throw new DeadLetterNotReplayableException(deadLetterId, "it was already replayed");
        }
        lifecycleService.requeueFailed(deadLetter.getNotificationId());
        deadLetter.setReplayedAt(LocalDateTime.now());
        return toResponse(deadLetter);
    }

    private static DeadLetterResponse toResponse(DeadLetter deadLetter) {
        return new DeadLetterResponse(deadLetter.getId(), deadLetter.getNotificationId(), deadLetter.getReason(),
                deadLetter.getPayload(), deadLetter.getCorrelationId(), deadLetter.getReceivedAt(),
                deadLetter.getReplayedAt());
    }
}
