package com.vatsasarthak.notifyr.repository;

import com.vatsasarthak.notifyr.entity.OutboxEvent;
import com.vatsasarthak.notifyr.entity.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    // Next batch of publishable events, locked for this transaction. SKIP LOCKED lets several
    // application instances relay concurrently without publishing the same row at the same time.
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status = 'PENDING' AND available_at <= :now
            ORDER BY id LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockNextBatch(@Param("now") LocalDateTime now, @Param("limit") int limit);

    long countByStatus(OutboxStatus status);

    @Query("select min(o.createdAt) from OutboxEvent o where o.status = :status")
    LocalDateTime findOldestCreatedAt(@Param("status") OutboxStatus status);

    @Modifying
    @Transactional
    @Query(value = """
            DELETE FROM outbox_events WHERE id IN (
                SELECT id FROM outbox_events WHERE status = 'SENT' AND sent_at < :sentBefore LIMIT :limit)
            """, nativeQuery = true)
    int deleteSentBefore(@Param("sentBefore") LocalDateTime sentBefore, @Param("limit") int limit);
}
