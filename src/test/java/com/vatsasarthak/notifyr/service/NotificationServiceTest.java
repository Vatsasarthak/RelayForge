package com.vatsasarthak.notifyr.service;

import com.vatsasarthak.notifyr.dto.NotificationRequest;
import com.vatsasarthak.notifyr.dto.NotificationResponse;
import com.vatsasarthak.notifyr.entity.Notification;
import com.vatsasarthak.notifyr.entity.NotificationChannel;
import com.vatsasarthak.notifyr.entity.NotificationStatus;
import com.vatsasarthak.notifyr.exception.IdempotencyConflictException;
import com.vatsasarthak.notifyr.exception.NotificationNotFoundException;
import com.vatsasarthak.notifyr.repository.NotificationRepository;
import com.vatsasarthak.notifyr.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class NotificationServiceTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private NotificationLifecycleService lifecycleService;
    @Mock private RedisTemplate<String, NotificationResponse> redisTemplate;
    @Mock private ValueOperations<String, NotificationResponse> valueOperations;
    @Mock private TransactionTemplate transactionTemplate;

    private NotificationService notificationService;
    private NotificationRequest request;
    private String cacheKey;

    @BeforeEach
    void setUp(){
        notificationService = new NotificationService(notificationRepository, lifecycleService, redisTemplate,
                transactionTemplate);
        request = new NotificationRequest("test@example.com", "Test Subject", "Test Message");
        cacheKey = NotificationService.cacheKey("alice", "key-1", NotificationService.hash(request));
    }

    @Test
    void sendNotification_withCachedResponse_returnsItWithoutTouchingTheDatabase(){
        NotificationResponse cached = new NotificationResponse();
        cached.setId(99L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(cacheKey)).thenReturn(cached);

        NotificationResponse result = notificationService.sendNotification(request, "alice", "key-1");

        assertThat(result.getId()).isEqualTo(99L);
        verifyNoInteractions(notificationRepository, lifecycleService, transactionTemplate);
    }

    @Test
    void sendNotification_newKey_persistsAndEnqueuesInOneTransaction_thenCaches(){
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(notificationRepository.findByOwnerAndIdempotencyKey("alice", "key-1")).thenReturn(Optional.empty());
        runTransactionCallbacksDirectly();
        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> {
            Notification n = invocation.getArgument(0);
            n.setId(1L);
            return n;
        });

        NotificationResponse result = notificationService.sendNotification(request, "alice", "key-1");

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(saved.capture());
        assertThat(saved.getValue().getOwner()).isEqualTo("alice");
        assertThat(saved.getValue().getIdempotencyKey()).isEqualTo("key-1");
        assertThat(saved.getValue().getRequestHash()).hasSize(64);
        assertThat(saved.getValue().getStatus()).isEqualTo(NotificationStatus.QUEUED);
        verify(lifecycleService).enqueueDelivery(saved.getValue());
        assertThat(result.getId()).isEqualTo(1L);
        verify(valueOperations).set(eq(cacheKey), any(NotificationResponse.class), any());
    }

    @Test
    void sendNotification_keyAlreadyInDatabase_returnsExistingNotification(){
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(notificationRepository.findByOwnerAndIdempotencyKey("alice", "key-1"))
                .thenReturn(Optional.of(stored(7L, NotificationService.hash(request))));

        NotificationResponse result = notificationService.sendNotification(request, "alice", "key-1");

        assertThat(result.getId()).isEqualTo(7L);
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void sendNotification_keyReusedWithDifferentBody_isRejected(){
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(notificationRepository.findByOwnerAndIdempotencyKey("alice", "key-1"))
                .thenReturn(Optional.of(stored(7L, "hash-of-another-body")));

        assertThatThrownBy(() -> notificationService.sendNotification(request, "alice", "key-1"))
                .isInstanceOf(IdempotencyConflictException.class);
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void sendNotification_losingAConcurrentInsert_returnsTheWinnersNotification(){
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(notificationRepository.findByOwnerAndIdempotencyKey("alice", "key-1"))
                .thenReturn(Optional.empty(), Optional.of(stored(42L, NotificationService.hash(request))));
        when(transactionTemplate.execute(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThat(notificationService.sendNotification(request, "alice", "key-1").getId()).isEqualTo(42L);
    }

    @Test
    void sendNotification_whenRedisIsDown_stillUsesTheDatabaseForIdempotency(){
        when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("redis down"));
        when(notificationRepository.findByOwnerAndIdempotencyKey("alice", "key-1"))
                .thenReturn(Optional.of(stored(7L, NotificationService.hash(request))));

        assertThat(notificationService.sendNotification(request, "alice", "key-1").getId()).isEqualTo(7L);
    }

    @Test
    void sendNotification_withoutIdempotencyKey_skipsRedisEntirely(){
        runTransactionCallbacksDirectly();
        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> invocation.getArgument(0));

        notificationService.sendNotification(request, "alice", null);

        verify(redisTemplate, never()).opsForValue();
        verify(notificationRepository, never()).findByOwnerAndIdempotencyKey(any(), any());
    }

    @Test
    void getNotification_ofAnotherUser_isReportedAsNotFound_butAdminCanSeeIt(){
        Notification bobs = stored(5L, null);
        bobs.setOwner("bob");
        when(notificationRepository.findById(5L)).thenReturn(Optional.of(bobs));

        assertThatThrownBy(() -> notificationService.getNotification(5L, new CurrentUser("alice", false)))
                .isInstanceOf(NotificationNotFoundException.class);
        assertThat(notificationService.getNotification(5L, new CurrentUser("bob", false)).getId()).isEqualTo(5L);
        assertThat(notificationService.getNotification(5L, new CurrentUser("root", true)).getId()).isEqualTo(5L);
    }

    @Test
    void hash_differsWhenAnyFieldDiffers(){
        String base = NotificationService.hash(request);
        assertThat(NotificationService.hash(new NotificationRequest("test@example.com", "Test Subject", "Other")))
                .isNotEqualTo(base);
        assertThat(NotificationService.hash(new NotificationRequest("test@example.com", "Test SubjectTest", " Message")))
                .isNotEqualTo(base);
        assertThat(NotificationService.hash(new NotificationRequest("test@example.com", "Test Subject", "Test Message")))
                .isEqualTo(base);
    }

    // regression: "alice:x" + key "y" and "alice" + key "x:y" used to map to the same Redis key
    @Test
    void cacheKey_isUnambiguousAcrossUsersAndKeys(){
        String bodyHash = NotificationService.hash(request);
        assertThat(NotificationService.cacheKey("alice:x", "y", bodyHash))
                .isNotEqualTo(NotificationService.cacheKey("alice", "x:y", bodyHash))
                .startsWith("idempotency:");
        assertThat(NotificationService.cacheKey("alice", "key-1", bodyHash))
                .isEqualTo(NotificationService.cacheKey("alice", "key-1", bodyHash));
    }

    private Notification stored(Long id, String requestHash) {
        return Notification.builder()
                .id(id).owner("alice").idempotencyKey("key-1").requestHash(requestHash)
                .recipientEmail("test@example.com").subject("Test Subject").message("Test Message")
                .channel(NotificationChannel.EMAIL).status(NotificationStatus.QUEUED)
                .build();
    }

    @SuppressWarnings("unchecked")
    private void runTransactionCallbacksDirectly() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(null));
    }
}
