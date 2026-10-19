package com.vatsasarthak.notifyr.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vatsasarthak.notifyr.entity.OutboxEvent;
import com.vatsasarthak.notifyr.entity.OutboxStatus;
import com.vatsasarthak.notifyr.repository.OutboxEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock private OutboxEventRepository repository;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private TransactionTemplate transactionTemplate;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        relay = new OutboxRelay(repository, rabbitTemplate, transactionTemplate, new ObjectMapper(), meterRegistry);
        ReflectionTestUtils.setField(relay, "batchSize", 10);
        ReflectionTestUtils.setField(relay, "confirmTimeoutMs", 1000L);
    }

    @Test
    void confirmedBatchIsMarkedSent() {
        OutboxEvent first = pending(1L);
        OutboxEvent second = pending(2L);
        when(repository.lockNextBatch(any(), anyInt())).thenReturn(List.of(first, second));

        assertThat(relay.publishBatch()).isEqualTo(2);

        assertThat(first.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(second.getSentAt()).isNotNull();
        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(meterRegistry.counter("outbox.events.published").count()).isEqualTo(2.0);
    }

    @Test
    void failedPublishKeepsEventsPendingWithBackoff() {
        OutboxEvent event = pending(1L);
        when(repository.lockNextBatch(any(), anyInt())).thenReturn(List.of(event));
        when(rabbitTemplate.invoke(any())).thenThrow(new AmqpException("connection refused"));

        LocalDateTime before = LocalDateTime.now();
        assertThat(relay.publishBatch()).isZero();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).isEqualTo("connection refused");
        assertThat(event.getAvailableAt()).isAfter(before);
        assertThat(meterRegistry.counter("outbox.events.publish.failures").count()).isEqualTo(1.0);
    }

    @Test
    void emptyOutboxPublishesNothing() {
        when(repository.lockNextBatch(any(), anyInt())).thenReturn(List.of());

        assertThat(relay.publishBatch()).isZero();
        verify(rabbitTemplate, never()).invoke(any());
    }

    @Test
    void backoffGrowsExponentiallyAndIsCapped() {
        assertThat(OutboxRelay.backoff(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(OutboxRelay.backoff(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(OutboxRelay.backoff(4)).isEqualTo(Duration.ofSeconds(8));
        assertThat(OutboxRelay.backoff(20)).isEqualTo(Duration.ofMinutes(1));
    }

    private OutboxEvent pending(Long id) {
        return OutboxEvent.builder()
                .id(id).aggregateType(OutboxEvent.AGGREGATE_NOTIFICATION).aggregateId(id)
                .exchange("notification.exchange").routingKey("notification.route")
                .payload("{\"notificationId\":" + id + "}").correlationId("corr-" + id)
                .availableAt(LocalDateTime.now()).createdAt(LocalDateTime.now())
                .build();
    }
}
