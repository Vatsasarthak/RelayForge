package com.vatsasarthak.notifyr.repository;

import com.vatsasarthak.notifyr.entity.Notification;
import com.vatsasarthak.notifyr.entity.NotificationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Optional<Notification> findByOwnerAndIdempotencyKey(String owner, String idempotencyKey);

    Page<Notification> findByOwner(String owner, Pageable pageable);

    Page<Notification> findByOwnerAndStatus(String owner, NotificationStatus status, Pageable pageable);

    Page<Notification> findByStatus(NotificationStatus status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from Notification n where n.id = :id")
    Optional<Notification> findByIdForUpdate(@Param("id") Long id);

    // Atomically takes ownership of a notification for delivery. Only one worker can win; a
    // PROCESSING row whose lease expired (worker crashed) can be taken over. The message must be for the
    // notification's current attempt: (id, retryCount) identifies a delivery job, so stale or duplicate
    // copies of earlier attempts are rejected. Returns 0 if not claimed.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Notification n set n.status = :processing, n.updatedAt = :now
            where n.id = :id and n.retryCount = :attempt
              and (n.status = :queued or (n.status = :processing and n.updatedAt < :leaseExpiredBefore))
            """)
    int claim(@Param("id") Long id,
              @Param("attempt") int attempt,
              @Param("now") LocalDateTime now,
              @Param("leaseExpiredBefore") LocalDateTime leaseExpiredBefore,
              @Param("queued") NotificationStatus queued,
              @Param("processing") NotificationStatus processing);

    // PROCESSING -> SENT/SIMULATED; returns 0 if the notification is no longer PROCESSING
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Notification n
            set n.status = :deliveredStatus, n.sentAt = :now, n.updatedAt = :now, n.failureReason = null
            where n.id = :id and n.status = :processing
            """)
    int markDelivered(@Param("id") Long id,
                      @Param("deliveredStatus") NotificationStatus deliveredStatus,
                      @Param("now") LocalDateTime now,
                      @Param("processing") NotificationStatus processing);

    @Query("select n.status, count(n) from Notification n where n.campaignId = :campaignId group by n.status")
    List<Object[]> countByStatusForCampaign(@Param("campaignId") Long campaignId);

    // Recovery: PROCESSING rows whose worker lease expired. SKIP LOCKED lets several instances run it.
    @Query(value = """
            SELECT id FROM notifications
            WHERE status = 'PROCESSING' AND updated_at < :leaseExpiredBefore
            ORDER BY id LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> lockExpiredProcessing(@Param("leaseExpiredBefore") LocalDateTime leaseExpiredBefore,
                                     @Param("limit") int limit);

    // Recovery: QUEUED rows untouched for a long time that have no message waiting in the outbox,
    // i.e. their broker message was lost (queue purged, message dropped after listener errors, ...).
    @Query(value = """
            SELECT n.id FROM notifications n
            WHERE n.status = 'QUEUED' AND n.updated_at < :staleBefore
              AND NOT EXISTS (SELECT 1 FROM outbox_events o
                              WHERE o.status = 'PENDING' AND o.aggregate_type = 'NOTIFICATION'
                                AND o.aggregate_id = n.id)
            ORDER BY n.id LIMIT :limit
            FOR UPDATE OF n SKIP LOCKED
            """, nativeQuery = true)
    List<Long> lockStaleQueued(@Param("staleBefore") LocalDateTime staleBefore, @Param("limit") int limit);
}
