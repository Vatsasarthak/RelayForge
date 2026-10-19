package com.vatsasarthak.notifyr.config;

import com.vatsasarthak.notifyr.entity.OutboxStatus;
import com.vatsasarthak.notifyr.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

// "outbox" health component and gauges. A growing or ageing backlog means messages are not reaching
// RabbitMQ (broker down, wrong credentials, ...) even though API requests still succeed.
@Component("outbox")
public class OutboxHealthIndicator implements HealthIndicator {

    private final OutboxEventRepository outboxEventRepository;

    @Value("${notification.outbox.max-healthy-age:PT5M}")
    private Duration maxHealthyAge;

    public OutboxHealthIndicator(OutboxEventRepository outboxEventRepository, MeterRegistry meterRegistry) {
        this.outboxEventRepository = outboxEventRepository;
        Gauge.builder("outbox.events.pending", outboxEventRepository,
                        repository -> repository.countByStatus(OutboxStatus.PENDING))
                .description("Outbox events not yet published to RabbitMQ")
                .register(meterRegistry);
        Gauge.builder("outbox.oldest.pending.age", this, indicator -> indicator.oldestPendingAge().toMillis() / 1000.0)
                .description("Age of the oldest unpublished outbox event")
                .baseUnit("seconds")
                .register(meterRegistry);
    }

    @Override
    public Health health() {
        long pending = outboxEventRepository.countByStatus(OutboxStatus.PENDING);
        Duration oldestAge = oldestPendingAge();
        Health.Builder builder = oldestAge.compareTo(maxHealthyAge) > 0 ? Health.down() : Health.up();
        return builder
                .withDetail("pendingEvents", pending)
                .withDetail("oldestPendingAgeSeconds", oldestAge.toSeconds())
                .build();
    }

    private Duration oldestPendingAge() {
        LocalDateTime oldest = outboxEventRepository.findOldestCreatedAt(OutboxStatus.PENDING);
        return oldest == null ? Duration.ZERO : Duration.between(oldest, LocalDateTime.now());
    }
}
