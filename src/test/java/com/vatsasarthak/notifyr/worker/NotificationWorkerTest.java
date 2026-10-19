package com.vatsasarthak.notifyr.worker;

import com.vatsasarthak.notifyr.dto.NotificationMessage;
import com.vatsasarthak.notifyr.entity.Notification;
import com.vatsasarthak.notifyr.entity.NotificationChannel;
import com.vatsasarthak.notifyr.entity.NotificationStatus;
import com.vatsasarthak.notifyr.provider.EmailProvider;
import com.vatsasarthak.notifyr.provider.PermanentDeliveryException;
import com.vatsasarthak.notifyr.provider.SmsProvider;
import com.vatsasarthak.notifyr.service.NotificationLifecycleService;
import com.vatsasarthak.notifyr.service.NotificationLifecycleService.FailureOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationWorkerTest {

    @Mock private NotificationLifecycleService lifecycleService;
    @Mock private EmailProvider emailProvider;
    @Mock private SmsProvider smsProvider;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private NotificationWorker worker;

    @BeforeEach
    void setUp() {
        worker = new NotificationWorker(lifecycleService, emailProvider, smsProvider, meterRegistry);
        ReflectionTestUtils.setField(worker, "simulateFailureRate", 0.0);
        ReflectionTestUtils.setField(worker, "pushDelayMs", 0L);
    }

    @Test
    void realEmailDeliveryIsRecordedAsSentWithLatency() throws Exception {
        claimable(1L, NotificationChannel.EMAIL);
        when(lifecycleService.markDelivered(1L, false)).thenReturn(true);

        worker.handleNotification(message(1L), "corr-1");

        verify(emailProvider).send("user@example.com", "Subject", "Body");
        verify(lifecycleService).markDelivered(1L, false);
        assertThat(meterRegistry.counter("notifications_sent_total", "channel", "EMAIL").count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("notification.delivery.latency").timer().count()).isEqualTo(1);
    }

    @Test
    void mockProviderDeliveryIsRecordedAsSimulatedNotSent() {
        Notification notification = claimable(2L, NotificationChannel.SMS);
        notification.setRecipientPhone("+15550000000");
        when(smsProvider.isSimulated()).thenReturn(true);
        when(lifecycleService.markDelivered(2L, true)).thenReturn(true);

        worker.handleNotification(message(2L), null);

        verify(lifecycleService).markDelivered(2L, true);
        assertThat(meterRegistry.counter("notifications_simulated_total", "channel", "SMS").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("notifications_sent_total", "channel", "SMS").count()).isZero();
    }

    @Test
    void pushIsAlwaysSimulated() {
        Notification notification = claimable(3L, NotificationChannel.PUSH);
        notification.setRecipientDeviceToken("device-token");

        worker.handleNotification(message(3L), null);

        verify(lifecycleService).markDelivered(3L, true);
    }

    @Test
    void transientFailureIsRecordedForRetry() throws Exception {
        claimable(4L, NotificationChannel.EMAIL);
        doThrow(new RuntimeException("SMTP down")).when(emailProvider).send(anyString(), anyString(), anyString());
        when(lifecycleService.recordFailure(4L, "SMTP down", false)).thenReturn(FailureOutcome.RETRY_SCHEDULED);

        worker.handleNotification(message(4L), null);

        verify(lifecycleService).recordFailure(4L, "SMTP down", false);
        assertThat(meterRegistry.counter("retry_attempts_total", "channel", "EMAIL").count()).isEqualTo(1.0);
    }

    @Test
    void permanentFailureSkipsRetries() throws Exception {
        claimable(5L, NotificationChannel.EMAIL);
        doThrow(new PermanentDeliveryException("Recipient email address is missing"))
                .when(emailProvider).send(anyString(), anyString(), anyString());
        when(lifecycleService.recordFailure(eq(5L), anyString(), eq(true))).thenReturn(FailureOutcome.FAILED);

        worker.handleNotification(message(5L), null);

        verify(lifecycleService).recordFailure(5L, "Recipient email address is missing", true);
        assertThat(meterRegistry.counter("notifications_failed_total", "channel", "EMAIL", "reason", "permanent")
                .count()).isEqualTo(1.0);
    }

    @Test
    void pushWithoutDeviceTokenIsAPermanentFailure() {
        claimable(6L, NotificationChannel.PUSH);
        when(lifecycleService.recordFailure(eq(6L), anyString(), eq(true))).thenReturn(FailureOutcome.FAILED);

        worker.handleNotification(message(6L), null);

        verify(lifecycleService).recordFailure(6L, "Recipient device token is missing", true);
    }

    @Test
    void claimUsesTheAttemptCarriedByTheMessage() {
        when(lifecycleService.claim(8L, 2)).thenReturn(Optional.empty());

        worker.handleNotification(new NotificationMessage(8L, "user@example.com", "Subject", "Body", 2), null);

        verify(lifecycleService).claim(8L, 2);
        verifyNoInteractions(emailProvider);
    }

    @Test
    void notificationThatCannotBeClaimedIsSkipped() {
        when(lifecycleService.claim(7L, 0)).thenReturn(Optional.empty());

        worker.handleNotification(message(7L), null);

        verifyNoInteractions(emailProvider, smsProvider);
        assertThat(meterRegistry.counter("notifications_duplicate_messages_total").count()).isEqualTo(1.0);
    }

    private Notification claimable(Long id, NotificationChannel channel) {
        Notification notification = Notification.builder()
                .id(id).channel(channel).status(NotificationStatus.PROCESSING)
                .recipientEmail("user@example.com").subject("Subject").message("Body")
                .createdAt(LocalDateTime.now().minusSeconds(1))
                .build();
        when(lifecycleService.claim(id, 0)).thenReturn(Optional.of(notification));
        return notification;
    }

    private NotificationMessage message(Long id) {
        return new NotificationMessage(id, "user@example.com", "Subject", "Body", 0);
    }
}
