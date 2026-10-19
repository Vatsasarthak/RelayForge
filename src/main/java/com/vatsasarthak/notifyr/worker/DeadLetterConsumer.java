package com.vatsasarthak.notifyr.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.vatsasarthak.notifyr.config.CorrelationIdFilter;
import com.vatsasarthak.notifyr.entity.DeadLetter;
import com.vatsasarthak.notifyr.repository.DeadLetterRepository;
import com.vatsasarthak.notifyr.service.NotificationLifecycleService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

// Drains notification.dlq into the dead_letters table, where admins can inspect and replay them
// (GET /admin/dead-letters, POST /admin/dead-letters/{id}/replay).
// Uses manual acknowledgement: a message is acked only after it is stored. If the database is down
// the message is returned to the queue after a pause, so nothing is lost and nothing spins.
@Component
@RequiredArgsConstructor
@Slf4j
public class DeadLetterConsumer {

    // set by RepublishMessageRecoverer for messages that failed inside a listener
    private static final String RECOVERER_EXCEPTION_HEADER = "x-exception-message";

    private final DeadLetterRepository deadLetterRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Value("${notification.dlq.store-failure-pause-ms:5000}")
    private long storeFailurePauseMs;

    @RabbitListener(queues = "${notification.dlq.queue}", containerFactory = "deadLetterContainerFactory")
    public void handle(Message message, Channel channel) throws IOException {
        MessageProperties properties = message.getMessageProperties();
        long deliveryTag = properties.getDeliveryTag();
        String correlationId = headerValue(properties, CorrelationIdFilter.HEADER);
        CorrelationIdFilter.putFromMessage(correlationId);
        try {
            String payload = new String(message.getBody(), StandardCharsets.UTF_8);
            DeadLetter deadLetter = DeadLetter.builder()
                    .notificationId(readNotificationId(payload))
                    .reason(firstNonNull(headerValue(properties, NotificationLifecycleService.FAILURE_REASON_HEADER),
                            headerValue(properties, RECOVERER_EXCEPTION_HEADER), "unknown"))
                    .payload(payload)
                    .correlationId(correlationId)
                    .build();
            deadLetterRepository.save(deadLetter);
            channel.basicAck(deliveryTag, false);

            meterRegistry.counter("dead_letter_messages_total").increment();
            log.warn("Dead letter recorded: id={} notificationId={} reason={}",
                    deadLetter.getId(), deadLetter.getNotificationId(), deadLetter.getReason());
        } catch (Exception e) {
            log.error("Could not record dead letter, returning it to the queue: {}", e.getMessage());
            pause();
            channel.basicNack(deliveryTag, false, true);
        } finally {
            CorrelationIdFilter.clear();
        }
    }

    private Long readNotificationId(String payload) {
        try {
            JsonNode id = objectMapper.readTree(payload).get("notificationId");
            return id != null && id.canConvertToLong() ? id.asLong() : null;
        } catch (Exception e) {
            return null;   // not JSON: a poison message, stored as-is
        }
    }

    private static String headerValue(MessageProperties properties, String name) {
        Object value = properties.getHeaders().get(name);
        return value != null ? value.toString() : null;
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private void pause() {
        try {
            Thread.sleep(storeFailurePauseMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
