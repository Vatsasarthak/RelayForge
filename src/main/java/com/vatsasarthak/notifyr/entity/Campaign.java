package com.vatsasarthak.notifyr.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "campaigns")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Campaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationChannel channel;

    @Column(nullable = false)
    private Long templateId;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "campaign_recipients", joinColumns = @JoinColumn(name = "campaign_id"))
    @Column(name = "recipient_id")
    @Builder.Default
    private List<Long> recipientIds = new ArrayList<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "campaign_audience_tags", joinColumns = @JoinColumn(name = "campaign_id"))
    @Column(name = "tag")
    @Builder.Default
    private List<String> audienceTags = new ArrayList<>();

    private String audienceLanguage;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "campaign_template_variables", joinColumns = @JoinColumn(name = "campaign_id"))
    @MapKeyColumn(name = "var_key")
    @Column(name = "var_value")
    @Builder.Default
    private Map<String, String> templateVariables = new HashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private CampaignStatus status = CampaignStatus.DRAFT;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime scheduledTime;

    // username of the admin who created it; owns the campaign's notifications
    @Column(length = 100)
    private String createdBy;

    @PrePersist
    protected void onCreate(){
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate(){
        this.updatedAt = LocalDateTime.now();
    }

}
