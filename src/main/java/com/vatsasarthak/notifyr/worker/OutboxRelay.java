package com.vatsasarthak.notifyr.worker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vatsasarthak.notifyr.config.CorrelationIdFilter;
import com.vatsasarthak.notifyr.entity.OutboxEvent;
import com.vatsasarthak.notifyr.entity.OutboxStatus;
import com.vatsasarthak.notifyr.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

// Publishes pending outbox events to RabbitMQ with publisher confirms and marks them SENT only after
// the broker confirmed them. A batch that fails is retried later with exponential backoff.
// Delivery to the broker is therefore at-least-once: a crash after the confirm but before the commit
// republishes the batch, which consumers tolerate (NotificationWorker claims each notification once).
@Component
@Slf4j
public class OutboxRelay {

    private static final Duration MAX_BACKOFF = Duration.ofMinutes(1);

    private final OutboxEventRepository outboxEventRepository;
    private final RabbitTemplate rabbitTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final Counter publishedCounter;
    private final Counter failedCounter;

    @Value("${notification.outbox.batch-size:200}")
    private int batchSize;

    @Value("${notification.outbox.confirm-timeout-ms:5000}")
    private long confirmTimeoutMs;

    @Value("${notification.outbox.retention-hours:24}")
    private long retentionHours;

    public OutboxRelay(OutboxEventRepository outboxEventRepository, RabbitTemplate rabbitTemplate,
                       TransactionTemplate transactionTemplate, ObjectMapper objectMapper,
                       MeterRegistry meterRegistry) {
        this.outboxEventRepository = outboxEventRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.publishedCounter = Counter.builder("outbox.events.published")
                .description("Outbox events confirmed by RabbitMQ").register(meterRegistry);
        this.failedCounter = Counter.builder("outbox.events.publish.failures")
                .description("Outbox events whose publication failed and will be retried").register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${notification.outbox.poll-interval-ms:100}")
    public void publishPending() {
        // keep draining while full batches come back, so a backlog is cleared without waiting for the next tick
        Integer published;
        do {
            published = transactionTemplate.execute(status -> publishBatch());
        } while (published != null && published == batchSize);
    }

    // runs inside a transaction that holds the row locks of the batch
    int publishBatch() {
        List<OutboxEvent> batch = outboxEventRepository.lockNextBatch(LocalDateTime.now(), batchSize);
        if (batch.isEmpty()) {
            return 0;
        }

        try {
            rabbitTemplate.invoke(operations -> {
                for (OutboxEvent event : batch) {
                    operations.send(event.getExchange(), event.getRoutingKey(), toMessage(event));
                }
                operations.waitForConfirmsOrDie(confirmTimeoutMs);
                return null;
            });
        } catch (Exception e) {
            LocalDateTime now = LocalDateTime.now();
            for (OutboxEvent event : batch) {
                event.setAttempts(event.getAttempts() + 1);
                event.setLastError(e.getMessage());
                event.setAvailableAt(now.plus(backoff(event.getAttempts())));
            }
            failedCounter.increment(batch.size());
            log.warn("Outbox: publishing {} event(s) failed, will retry: {}", batch.size(), e.getMessage());
            return 0;
        }

        LocalDateTime now = LocalDateTime.now();
        for (OutboxEvent event : batch) {
            event.setStatus(OutboxStatus.SENT);
            event.setSentAt(now);
            event.setAttempts(event.getAttempts() + 1);
            event.setLastError(null);
        }
        publishedCounter.increment(batch.size());
        return batch.size();
    }

    // published rows are only kept for troubleshooting
    @Scheduled(fixedDelayString = "${notification.outbox.cleanup-interval-ms:600000}")
    public void deleteOldSentEvents() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(retentionHours);
        int deleted;
        int total = 0;
        do {
            deleted = outboxEventRepository.deleteSentBefore(cutoff, 1000);
            total += deleted;
        } while (deleted == 1000);
        if (total > 0) {
            log.info("Outbox: deleted {} published event(s) older than {} h", total, retentionHours);
        }
    }

    static Duration backoff(int attempts) {
        long seconds = 1L << Math.min(attempts - 1, 6);   // 1, 2, 4, ... 64 s
        Duration delay = Duration.ofSeconds(seconds);
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
    }

    private Message toMessage(OutboxEvent event) {
        MessageBuilder builder = MessageBuilder.withBody(event.getPayload().getBytes(StandardCharsets.UTF_8));
        builder.setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId("outbox-" + event.getId());
        if (event.getCorrelationId() != null) {
            builder.setHeader(CorrelationIdFilter.HEADER, event.getCorrelationId());
        }
        if (event.getMessageHeaders() != null) {
            readHeaders(event.getMessageHeaders()).forEach(builder::setHeader);
        }
        return builder.build();
    }

    private Map<String, String> readHeaders(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Outbox: ignoring unreadable message headers: {}", e.getMessage());
            return Map.of();
        }
    }
}
