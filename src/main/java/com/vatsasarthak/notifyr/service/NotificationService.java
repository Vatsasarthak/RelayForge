package com.vatsasarthak.notifyr.service;

import com.vatsasarthak.notifyr.dto.NotificationRequest;
import com.vatsasarthak.notifyr.dto.NotificationResponse;
import com.vatsasarthak.notifyr.dto.PageResponse;
import com.vatsasarthak.notifyr.entity.Notification;
import com.vatsasarthak.notifyr.entity.NotificationChannel;
import com.vatsasarthak.notifyr.entity.NotificationStatus;
import com.vatsasarthak.notifyr.exception.IdempotencyConflictException;
import com.vatsasarthak.notifyr.exception.NotificationNotFoundException;
import com.vatsasarthak.notifyr.repository.NotificationRepository;
import com.vatsasarthak.notifyr.security.PiiMasking;
import com.vatsasarthak.notifyr.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationLifecycleService lifecycleService;
    private final RedisTemplate<String, NotificationResponse> redisTemplate;
    private final TransactionTemplate transactionTemplate;

    private static final String IDEMPOTENCY_KEY_PREFIX = "idempotency:";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    // Idempotency (see ADR 0002): the unique index (owner, idempotency_key) in PostgreSQL is the source
    // of truth, so a retried request can never create a second notification - even if Redis lost its
    // data. Redis only caches the first response to answer replays without a database query.
    public NotificationResponse sendNotification(NotificationRequest request, String owner, String idempotencyKey){

        boolean useIdempotency = idempotencyKey != null && !idempotencyKey.isBlank();
        String requestHash = useIdempotency ? hash(request) : null;
        // the body hash is part of the cache key: a hit is a replay of the same request, while a key reused
        // with a different body misses the cache and is rejected by the database check below
        String redisKey = useIdempotency ? cacheKey(owner, idempotencyKey, requestHash) : null;

        if(useIdempotency) {
            NotificationResponse cachedResponse = readCache(redisKey);
            if (cachedResponse != null) {
                log.info("Idempotency-Key {} replayed from cache", idempotencyKey);
                return cachedResponse;
            }
            Optional<NotificationResponse> existing = replay(owner, idempotencyKey, requestHash);
            if (existing.isPresent()) {
                writeCache(redisKey, existing.get());
                return existing.get();
            }
        }

        NotificationResponse response;
        try {
            response = transactionTemplate.execute(status -> createAndEnqueue(request, owner, idempotencyKey, requestHash));
        } catch (DataIntegrityViolationException e) {
            // a concurrent request with the same key committed first: return its notification
            if (!useIdempotency) {
                throw e;
            }
            response = replay(owner, idempotencyKey, requestHash).orElseThrow(() -> e);
        }

        if(useIdempotency) {
            writeCache(redisKey, response);
        }
        return response;
    }

    // the notification and its queue message are committed together (transactional outbox)
    private NotificationResponse createAndEnqueue(NotificationRequest request, String owner, String idempotencyKey,
                                                  String requestHash) {
        Notification notification = Notification.builder()
                .recipientEmail(request.getRecipientEmail())
                .subject(request.getSubject())
                .message(request.getMessage())
                .status(NotificationStatus.QUEUED)
                .channel(NotificationChannel.EMAIL)
                .owner(owner)
                .idempotencyKey(idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey : null)
                .requestHash(requestHash)
                .build();

        notification = notificationRepository.save(notification);
        lifecycleService.enqueueDelivery(notification);

        log.info("Notification {} accepted for recipient {}", notification.getId(),
                PiiMasking.email(notification.getRecipientEmail()));
        return mapToResponse(notification);
    }

    private Optional<NotificationResponse> replay(String owner, String idempotencyKey, String requestHash) {
        return notificationRepository.findByOwnerAndIdempotencyKey(owner, idempotencyKey).map(existing -> {
            if (existing.getRequestHash() != null && !existing.getRequestHash().equals(requestHash)) {
                throw new IdempotencyConflictException(idempotencyKey);
            }
            log.info("Idempotency-Key {} already used, returning notification {}", idempotencyKey, existing.getId());
            return mapToResponse(existing);
        });
    }

    // Redis problems must not fail the request: the database still guarantees idempotency
    private NotificationResponse readCache(String redisKey) {
        try {
            return redisTemplate.opsForValue().get(redisKey);
        } catch (RuntimeException e) {
            log.warn("Idempotency cache read failed, falling back to the database: {}", e.getMessage());
            return null;
        }
    }

    private void writeCache(String redisKey, NotificationResponse response) {
        try {
            redisTemplate.opsForValue().set(redisKey, response, IDEMPOTENCY_TTL);
        } catch (RuntimeException e) {
            log.warn("Idempotency cache write failed: {}", e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public NotificationResponse getNotification(Long id, CurrentUser caller) {

        Notification notification = notificationRepository.findById(id)
                .filter(n -> caller.canAccess(n.getOwner()))
                // another user's notification is reported as missing, so ids cannot be probed
                .orElseThrow(() -> new NotificationNotFoundException("Notification: " + id + " not found"));

        return mapToResponse(notification);
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> list(CurrentUser caller, NotificationStatus status, Pageable pageable) {
        Page<Notification> page;
        if (caller.admin()) {
            page = status == null ? notificationRepository.findAll(pageable)
                    : notificationRepository.findByStatus(status, pageable);
        } else {
            page = status == null ? notificationRepository.findByOwner(caller.username(), pageable)
                    : notificationRepository.findByOwnerAndStatus(caller.username(), status, pageable);
        }
        return PageResponse.of(page, this::mapToResponse);
    }

    public NotificationResponse retry(Long id) {
        return mapToResponse(lifecycleService.requeueFailed(id));
    }

    public NotificationResponse mapToResponse(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getRecipientEmail(),
                notification.getStatus(),
                notification.getSubject(),
                notification.getFailureReason(),
                notification.getCreatedAt(),
                notification.getUpdatedAt(),
                notification.getChannel(),
                notification.getRetryCount(),
                notification.getCampaignId(),
                notification.getSentAt()
        );
    }

    static String hash(NotificationRequest request) {
        return sha256(request.getRecipientEmail(), request.getSubject(), request.getMessage());
    }

    // Hashes owner, key and body hash with a separator that cannot occur in them. Plain concatenation was
    // ambiguous: user "alice:x" with key "y" and user "alice" with key "x:y" produced the same Redis key.
    static String cacheKey(String owner, String idempotencyKey, String requestHash) {
        return IDEMPOTENCY_KEY_PREFIX + sha256(owner, idempotencyKey, requestHash);
    }

    private static String sha256(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update(String.valueOf(part).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
