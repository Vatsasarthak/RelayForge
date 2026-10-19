package com.vatsasarthak.notifyr.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// A message that reached notification.dlq, recorded by DeadLetterConsumer for inspection and replay.
@Entity
@Table(name = "dead_letters")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeadLetter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // null when the message was not a readable notification message (e.g. a poison message)
    private Long notificationId;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(length = 64)
    private String correlationId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime receivedAt;

    private LocalDateTime replayedAt;

    @PrePersist
    protected void onCreate() {
        this.receivedAt = LocalDateTime.now();
    }
}
