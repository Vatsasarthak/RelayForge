package com.vatsasarthak.notifyr.scheduler;

import com.vatsasarthak.notifyr.service.CampaignService;
import com.vatsasarthak.notifyr.service.NotificationLifecycleService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

// Safety net for at-least-once delivery:
//  - PROCESSING notifications whose worker died (lease expired) are re-queued, counting as an attempt;
//  - QUEUED notifications whose broker message disappeared are re-published;
//  - RUNNING campaigns whose dispatch was lost are dispatched again.
// Duplicate messages this may create are harmless: the worker claims each notification only once.
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationRecoveryJob {

    private static final int BATCH_SIZE = 500;

    private final NotificationLifecycleService lifecycleService;
    private final CampaignService campaignService;
    private final MeterRegistry meterRegistry;

    // longer than the longest retry delay (5 min), so notifications waiting in a retry queue are not touched
    @Value("${notification.recovery.stale-queued-after:PT15M}")
    private Duration staleQueuedAfter;

    @Scheduled(fixedDelayString = "${notification.recovery.interval-ms:60000}",
            initialDelayString = "${notification.recovery.initial-delay-ms:60000}")
    public void recover() {
        int expired = lifecycleService.recoverExpiredProcessing(BATCH_SIZE);
        int stale = lifecycleService.requeueStaleQueued(staleQueuedAfter, BATCH_SIZE);
        int campaigns = campaignService.redispatchStuckCampaigns(staleQueuedAfter, BATCH_SIZE);

        if (expired > 0) {
            meterRegistry.counter("notifications_recovered_total", "reason", "processing_lease_expired")
                    .increment(expired);
            log.warn("Recovery: re-queued {} notification(s) whose worker lease expired", expired);
        }
        if (stale > 0) {
            meterRegistry.counter("notifications_recovered_total", "reason", "queued_message_lost")
                    .increment(stale);
            log.warn("Recovery: re-published {} notification(s) stuck in QUEUED", stale);
        }
        if (campaigns > 0) {
            meterRegistry.counter("campaigns_recovered_total").increment(campaigns);
            log.warn("Recovery: re-dispatched {} campaign(s) stuck in RUNNING", campaigns);
        }
    }
}
