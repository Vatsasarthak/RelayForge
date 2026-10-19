package com.vatsasarthak.notifyr.service;

import com.vatsasarthak.notifyr.dto.NotificationMessage;
import com.vatsasarthak.notifyr.entity.Notification;
import com.vatsasarthak.notifyr.entity.NotificationStatus;
import com.vatsasarthak.notifyr.entity.OutboxEvent;
import com.vatsasarthak.notifyr.exception.InvalidNotificationStateException;
import com.vatsasarthak.notifyr.exception.NotificationNotFoundException;
import com.vatsasarthak.notifyr.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// All delivery state transitions of a notification. Each method is one transaction that changes the
// row and records the follow-up message (retry, dead letter, re-delivery) in the outbox, so state and
// messages cannot diverge. Provider calls happen outside these transactions (see NotificationWorker).
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationLifecycleService {

    // Pre-rename (Notifyr) name kept on purpose: it is stored in pending outbox rows and in dead-lettered
    // messages, which DeadLetterConsumer must still be able to read.
    public static final String FAILURE_REASON_HEADER = "x-notifyr-failure-reason";

    public enum FailureOutcome { RETRY_SCHEDULED, FAILED, IGNORED }

    private final NotificationRepository notificationRepository;
    private final OutboxService outboxService;

    @Value("${notification.exchange.name}")
    private String exchangeName;

    @Value("${notification.routing-key}")
    private String routingKey;

    @Value("${notification.retry.exchange}")
    private String retryExchangeName;

    @Value("${notification.retry.routing-key-30s}")
    private String retryRoutingKey30s;

    @Value("${notification.retry.routing-key-2m}")
    private String retryRoutingKey2m;

    @Value("${notification.retry.routing-key-5m}")
    private String retryRoutingKey5m;

    @Value("${notification.dlq.exchange}")
    private String dlqExchangeName;

    @Value("${notification.dlq.routing-key}")
    private String dlqRoutingKey;

    @Value("${notification.delivery.max-retries:3}")
    private int maxRetries;

    // a worker that holds a notification longer than this is presumed dead
    @Value("${notification.delivery.processing-lease:PT5M}")
    private Duration processingLease;

    // Takes the notification for delivery; empty if another worker has it, it is already finished, or the
    // message belongs to a different attempt than the notification's current one.
    @Transactional
    public Optional<Notification> claim(Long notificationId, int attempt) {
        LocalDateTime now = LocalDateTime.now();
        int claimed = notificationRepository.claim(notificationId, attempt, now, now.minus(processingLease),
                NotificationStatus.QUEUED, NotificationStatus.PROCESSING);
        if (claimed == 0) {
            return Optional.empty();
        }
        return notificationRepository.findById(notificationId);
    }

    @Transactional
    public boolean markDelivered(Long notificationId, boolean simulated) {
        int updated = notificationRepository.markDelivered(notificationId,
                simulated ? NotificationStatus.SIMULATED : NotificationStatus.SENT,
                LocalDateTime.now(), NotificationStatus.PROCESSING);
        if (updated == 0) {
            log.warn("Notification id={} was no longer PROCESSING when its delivery finished", notificationId);
        }
        return updated == 1;
    }

    // Transient failures are retried with increasing delays (30 s, 2 min, 5 min); permanent failures
    // and exhausted retries end in FAILED with a copy of the message in the dead-letter queue.
    @Transactional
    public FailureOutcome recordFailure(Long notificationId, String reason, boolean permanent) {
        Notification notification = notificationRepository.findByIdForUpdate(notificationId).orElse(null);
        if (notification == null || notification.getStatus() != NotificationStatus.PROCESSING) {
            log.warn("Notification id={} failure ignored, it is no longer PROCESSING", notificationId);
            return FailureOutcome.IGNORED;
        }

        int attempts = notification.getRetryCount() + 1;
        notification.setFailureReason(reason);

        if (permanent || attempts > maxRetries) {
            notification.setStatus(NotificationStatus.FAILED);
            notification.setRetryCount(attempts);
            outboxService.enqueue(OutboxEvent.AGGREGATE_NOTIFICATION, notificationId, dlqExchangeName, dlqRoutingKey,
                    toMessage(notification), Map.of(FAILURE_REASON_HEADER, truncate(reason)));
            return FailureOutcome.FAILED;
        }

        notification.setStatus(NotificationStatus.QUEUED);
        notification.setRetryCount(attempts);
        outboxService.enqueue(OutboxEvent.AGGREGATE_NOTIFICATION, notificationId, retryExchangeName,
                retryRoutingKey(attempts), toMessage(notification));
        return FailureOutcome.RETRY_SCHEDULED;
    }

    // Manual retry of a FAILED notification (admin action, also used for dead-letter replay).
    @Transactional
    public Notification requeueFailed(Long notificationId) {
        Notification notification = notificationRepository.findByIdForUpdate(notificationId)
                .orElseThrow(() -> new NotificationNotFoundException("Notification: " + notificationId + " not found"));
        if (notification.getStatus() != NotificationStatus.FAILED) {
            throw new InvalidNotificationStateException(notificationId, notification.getStatus(),
                    "only FAILED notifications can be retried");
        }
        notification.setStatus(NotificationStatus.QUEUED);
        notification.setRetryCount(0);
        notification.setFailureReason(null);
        enqueueDelivery(notification);
        return notification;
    }

    // Recovery for crashed workers: PROCESSING past its lease goes back to the queue. This counts as an
    // attempt, so a message that keeps killing workers still ends in FAILED instead of looping forever.
    @Transactional
    public int recoverExpiredProcessing(int limit) {
        List<Long> ids = notificationRepository.lockExpiredProcessing(LocalDateTime.now().minus(processingLease), limit);
        for (Long id : ids) {
            Notification notification = notificationRepository.findById(id).orElseThrow();
            int attempts = notification.getRetryCount() + 1;
            notification.setRetryCount(attempts);
            String reason = "Delivery attempt did not finish within " + processingLease + " (worker stopped?)";
            notification.setFailureReason(reason);
            if (attempts > maxRetries) {
                notification.setStatus(NotificationStatus.FAILED);
                outboxService.enqueue(OutboxEvent.AGGREGATE_NOTIFICATION, id, dlqExchangeName, dlqRoutingKey,
                        toMessage(notification), Map.of(FAILURE_REASON_HEADER, reason));
            } else {
                notification.setStatus(NotificationStatus.QUEUED);
                enqueueDelivery(notification);
            }
        }
        return ids.size();
    }

    // Recovery for lost broker messages: QUEUED for too long with nothing pending in the outbox.
    @Transactional
    public int requeueStaleQueued(Duration staleAfter, int limit) {
        List<Long> ids = notificationRepository.lockStaleQueued(LocalDateTime.now().minus(staleAfter), limit);
        for (Long id : ids) {
            Notification notification = notificationRepository.findById(id).orElseThrow();
            notification.setUpdatedAt(LocalDateTime.now());
            enqueueDelivery(notification);
        }
        return ids.size();
    }

    // must run inside the caller's transaction
    public void enqueueDelivery(Notification notification) {
        outboxService.enqueue(OutboxEvent.AGGREGATE_NOTIFICATION, notification.getId(), exchangeName, routingKey,
                toMessage(notification));
    }

    private String retryRoutingKey(int attempt) {
        return switch (attempt) {
            case 1 -> retryRoutingKey30s;
            case 2 -> retryRoutingKey2m;
            default -> retryRoutingKey5m;
        };
    }

    private static NotificationMessage toMessage(Notification notification) {
        return new NotificationMessage(notification.getId(), notification.getRecipientEmail(),
                notification.getSubject(), notification.getMessage(), notification.getRetryCount());
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }
}
