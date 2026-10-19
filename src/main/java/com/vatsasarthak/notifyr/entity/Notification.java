package com.vatsasarthak.notifyr.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "notifications")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String recipientEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationChannel channel;

    private String recipientPhone;

    private String recipientDeviceToken;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private NotificationStatus status = NotificationStatus.QUEUED;


    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Column(columnDefinition = "TEXT")
    private String failureReason;

    @Column(nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    // username of the caller who created it (campaign notifications: the campaign creator)
    @Column(length = 100)
    private String owner;

    private String idempotencyKey;

    // SHA-256 of the request body, to reject an Idempotency-Key reused with different content
    @Column(length = 64)
    private String requestHash;

    private Long campaignId;

    // when the provider accepted the message (or the simulation finished)
    private LocalDateTime sentAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
