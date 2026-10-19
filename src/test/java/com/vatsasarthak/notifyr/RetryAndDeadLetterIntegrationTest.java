package com.vatsasarthak.notifyr;

import com.vatsasarthak.notifyr.dto.DeadLetterResponse;
import com.vatsasarthak.notifyr.dto.NotificationRequest;
import com.vatsasarthak.notifyr.dto.NotificationResponse;
import com.vatsasarthak.notifyr.entity.DeadLetter;
import com.vatsasarthak.notifyr.entity.Notification;
import com.vatsasarthak.notifyr.entity.NotificationStatus;
import com.vatsasarthak.notifyr.repository.DeadLetterRepository;
import com.vatsasarthak.notifyr.repository.NotificationRepository;
import com.vatsasarthak.notifyr.service.DeadLetterService;
import com.vatsasarthak.notifyr.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Every delivery fails and the retry TTLs are shortened, so a notification walks the real RabbitMQ
// retry topology (retry queue -> TTL -> dead-lettered back to the main queue) three times, ends in the
// DLQ, is recorded by DeadLetterConsumer - and stops there instead of looping.
@SpringBootTest(properties = {
        "notification.simulate-failure-rate=1.0",
        "notification.retry.ttl-30s=100",
        "notification.retry.ttl-2m=100",
        "notification.retry.ttl-5m=100"
})
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class RetryAndDeadLetterIntegrationTest {

    @Autowired private NotificationService notificationService;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private DeadLetterRepository deadLetterRepository;
    @Autowired private DeadLetterService deadLetterService;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private RabbitAdmin rabbitAdmin;

    @Test
    void failingNotificationIsRetriedThreeTimesThenDeadLetteredAndCanBeReplayed() {
        NotificationResponse queued = notificationService.sendNotification(
                new NotificationRequest("retry@example.com", "Retry", "Always fails"), "retry-user", null);
        Long id = queued.getId();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Notification notification = notificationRepository.findById(id).orElseThrow();
            assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
            assertThat(notification.getRetryCount()).isEqualTo(4);   // first attempt + 3 retries
            assertThat(notification.getFailureReason()).isEqualTo("Simulated Provider Failure");
        });

        // the DLQ message is consumed into the dead_letters table, exactly once
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(deadLetterRepository.findByNotificationId(id)).hasSize(1));
        DeadLetter deadLetter = deadLetterRepository.findByNotificationId(id).get(0);
        assertThat(deadLetter.getReason()).isEqualTo("Simulated Provider Failure");
        assertThat(deadLetter.getPayload()).contains("\"notificationId\":" + id);

        // exhausted: no further attempts happen on their own
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(notificationRepository.findById(id).orElseThrow().getRetryCount()).isEqualTo(4));

        // admin replay puts it back into delivery with a fresh retry budget (it fails again here)
        DeadLetterResponse replayed = deadLetterService.replay(deadLetter.getId());
        assertThat(replayed.replayedAt()).isNotNull();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(deadLetterRepository.findByNotificationId(id)).hasSize(2));
        assertThat(notificationRepository.findById(id).orElseThrow().getStatus()).isEqualTo(NotificationStatus.FAILED);
    }

    @Test
    void poisonMessageIsDeadLetteredInsteadOfRedeliveredForever() {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbitTemplate.send("notification.exchange", "notification.route",
                new Message("this is not json".getBytes(StandardCharsets.UTF_8), properties));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            List<DeadLetter> poison = deadLetterRepository.findAll().stream()
                    .filter(d -> d.getPayload().equals("this is not json"))
                    .toList();
            assertThat(poison).hasSize(1);
            assertThat(poison.get(0).getNotificationId()).isNull();
        });

        Properties queue = rabbitAdmin.getQueueProperties("notification.queue");
        assertThat(queue.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).isEqualTo(0);
    }
}
