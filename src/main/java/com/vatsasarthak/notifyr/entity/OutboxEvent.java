package com.vatsasarthak.notifyr.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// A RabbitMQ message waiting to be published. Written in the same transaction as the state change
// that produced it, published afterwards by OutboxRelay (transactional outbox pattern).
@Entity
@Table(name = "outbox_events")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {

    public static final String AGGREGATE_NOTIFICATION = "NOTIFICATION";
    public static final String AGGREGATE_CAMPAIGN = "CAMPAIGN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String aggregateType;

    @Column(nullable = false)
    private Long aggregateId;

    @Column(nullable = false)
    private String exchange;

    @Column(nullable = false)
    private String routingKey;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    // extra AMQP headers as a JSON object, e.g. the failure reason of a dead-lettered notification
    @Column(columnDefinition = "TEXT")
    private String messageHeaders;

    @Column(length = 64)
    private String correlationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(nullable = false)
    @Builder.Default
    private Integer attempts = 0;

    @Column(columnDefinition = "TEXT")
    private String lastError;

    @Column(nullable = false)
    private LocalDateTime availableAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime sentAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.availableAt == null) {
            this.availableAt = this.createdAt;
        }
    }
}
