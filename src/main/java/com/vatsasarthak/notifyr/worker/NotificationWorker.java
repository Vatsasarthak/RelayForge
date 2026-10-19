package com.vatsasarthak.notifyr.worker;

import com.vatsasarthak.notifyr.config.CorrelationIdFilter;
import com.vatsasarthak.notifyr.dto.NotificationMessage;
import com.vatsasarthak.notifyr.entity.Notification;
import com.vatsasarthak.notifyr.entity.NotificationChannel;
import com.vatsasarthak.notifyr.provider.EmailProvider;
import com.vatsasarthak.notifyr.provider.PermanentDeliveryException;
import com.vatsasarthak.notifyr.provider.SmsProvider;
import com.vatsasarthak.notifyr.security.PiiMasking;
import com.vatsasarthak.notifyr.service.NotificationLifecycleService;
import com.vatsasarthak.notifyr.service.NotificationLifecycleService.FailureOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Random;

// Consumes notification.queue. Per message:
//   1. claim the notification (atomic QUEUED -> PROCESSING; duplicates and finished ones are skipped)
//   2. call the provider - outside any database transaction
//   3. record the outcome (delivered / retry scheduled / failed) through NotificationLifecycleService
// The message is acknowledged when this method returns. If the process dies between 1 and 3 the
// notification stays PROCESSING until its lease expires and NotificationRecoveryJob re-queues it,
// so delivery is at-least-once (see README "Delivery guarantees").
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationWorker {

    private final NotificationLifecycleService lifecycleService;
    private final EmailProvider emailProvider;
    private final SmsProvider smsProvider;
    private final MeterRegistry meterRegistry;

    @Value("${notification.simulate-failure-rate:0.0}")
    private double simulateFailureRate;

    @Value("${notification.push.delay-ms:1000}")
    private long pushDelayMs;

    private final Random random = new Random();

    @RabbitListener(queues = "${notification.queue.name}")
    public void handleNotification(NotificationMessage message,
                                   @Header(name = CorrelationIdFilter.HEADER, required = false) String correlationId){
        CorrelationIdFilter.putFromMessage(correlationId);
        try {
            process(message);
        } finally {
            CorrelationIdFilter.clear();
        }
    }

    private void process(NotificationMessage message) {
        Optional<Notification> claimed = lifecycleService.claim(message.getNotificationId(), message.getRetryCount());

        if(claimed.isEmpty()){
            // already delivered/failed, being delivered by another worker, a stale copy of an earlier
            // attempt, or unknown: nothing to do
            log.info("Notification id={} attempt={} not claimable, skipping message",
                    message.getNotificationId(), message.getRetryCount());
            meterRegistry.counter("notifications_duplicate_messages_total").increment();
            return;
        }

        Notification notification = claimed.get();
        String channel = notification.getChannel().toString();
        Timer.Sample providerCall = Timer.start(meterRegistry);
        String outcome = "success";

        try{
            boolean simulated = dispatchToProvider(notification);

            if(lifecycleService.markDelivered(notification.getId(), simulated)){
                meterRegistry.counter(simulated ? "notifications_simulated_total" : "notifications_sent_total",
                        "channel", channel).increment();
                Timer.builder("notification.delivery.latency")
                        .description("Time from accepting a notification until its delivery finished (incl. retries)")
                        .tag("channel", channel)
                        .publishPercentileHistogram()
                        // retried deliveries take up to ~7.5 min; the 30 s default range would cap the percentiles
                        .maximumExpectedValue(Duration.ofMinutes(15))
                        .register(meterRegistry)
                        .record(Duration.between(notification.getCreatedAt(), LocalDateTime.now()));
            }

            if(simulated){
                log.info("Notification id={} processed by a simulated {} channel - nothing was delivered",
                        notification.getId(), channel);
            }else{
                log.info("Notification id={} sent successfully", notification.getId());
            }

        }catch(PermanentDeliveryException e) {
            outcome = "permanent_failure";
            handleFailure(notification, e, true);
        }catch(Exception e) {
            outcome = "failure";
            handleFailure(notification, e, false);
        }finally {
            providerCall.stop(Timer.builder("notification.provider.duration")
                    .description("Duration of the provider call")
                    .tag("channel", channel)
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        }
    }

    // returns true when the channel only simulated the delivery
    private boolean dispatchToProvider(Notification notification) throws Exception {

        if(random.nextDouble() < simulateFailureRate){
            throw new RuntimeException("Simulated Provider Failure");
        }

        // get the channel
        NotificationChannel channel = notification.getChannel();

        switch(channel){    // route to proper provider
            case EMAIL -> {
                emailProvider.send(notification.getRecipientEmail(), notification.getSubject(), notification.getMessage());
                return emailProvider.isSimulated();
            }
            case SMS -> {
                smsProvider.send(notification.getRecipientPhone(), notification.getMessage());
                return smsProvider.isSimulated();
            }
            case PUSH -> {
                simulatePushSend(notification);
                return true;
            }
            default -> throw new PermanentDeliveryException("Unknown notification channel: " + channel);
        }
    }

    private void simulatePushSend(Notification notification) throws InterruptedException {
        if(notification.getRecipientDeviceToken() == null || notification.getRecipientDeviceToken().isBlank()){
            throw new PermanentDeliveryException("Recipient device token is missing");
        }
        Thread.sleep(pushDelayMs);  // simulate some processing time
        log.info("Simulating push notification to device token={} (not delivered - push is simulated)",
                PiiMasking.token(notification.getRecipientDeviceToken()));
    }

    private void handleFailure(Notification notification, Exception e, boolean permanent) {
        String channel = notification.getChannel().toString();
        FailureOutcome outcome = lifecycleService.recordFailure(notification.getId(), e.getMessage(), permanent);

        switch (outcome) {
            case RETRY_SCHEDULED -> {
                meterRegistry.counter("retry_attempts_total", "channel", channel).increment();
                log.warn("Notification id={} failed on attempt {}, retry scheduled. Error: {}",
                        notification.getId(), notification.getRetryCount() + 1, e.getMessage());
            }
            case FAILED -> {
                meterRegistry.counter("notifications_failed_total", "channel", channel,
                        "reason", permanent ? "permanent" : "retries_exhausted").increment();
                log.error("Notification id={} failed {}, sent to DLQ. Error: {}", notification.getId(),
                        permanent ? "permanently" : "after " + (notification.getRetryCount() + 1) + " attempts",
                        e.getMessage());
            }
            case IGNORED -> log.warn("Notification id={} failure not recorded (state changed meanwhile)",
                    notification.getId());
        }
    }

}
