package com.vatsasarthak.notifyr.repository;

import com.vatsasarthak.notifyr.dto.CampaignSummary;
import com.vatsasarthak.notifyr.entity.Campaign;
import com.vatsasarthak.notifyr.entity.CampaignStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {

    List<Campaign> findByStatusAndScheduledTimeLessThanEqual(CampaignStatus status, LocalDateTime scheduledTime);

    @Query(value = """
            select new com.vatsasarthak.notifyr.dto.CampaignSummary(
                c.id, c.name, c.description, c.channel, c.templateId,
                c.audienceLanguage, c.status, c.createdAt, c.updatedAt, c.scheduledTime)
            from Campaign c
            """,
            countQuery = "select count(c) from Campaign c")
    Page<CampaignSummary> findAllSummaries(Pageable pageable);

    // Row lock for the fan-out: a second (duplicate) dispatch message waits here and then sees COMPLETED.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Campaign c where c.id = :id")
    Optional<Campaign> findByIdForUpdate(@Param("id") Long id);

    // Recovery: RUNNING campaigns untouched for a long time with no dispatch message pending in the outbox
    // (the dispatch failed in a way that could not even record FAILED, e.g. the database was down).
    // A fan-out in progress holds the row lock, so SKIP LOCKED leaves it alone.
    @Query(value = """
            SELECT c.id FROM campaigns c
            WHERE c.status = 'RUNNING' AND c.updated_at < :staleBefore
              AND NOT EXISTS (SELECT 1 FROM outbox_events o
                              WHERE o.status = 'PENDING' AND o.aggregate_type = 'CAMPAIGN'
                                AND o.aggregate_id = c.id)
            ORDER BY c.id LIMIT :limit
            FOR UPDATE OF c SKIP LOCKED
            """, nativeQuery = true)
    List<Long> lockStuckRunning(@Param("staleBefore") LocalDateTime staleBefore, @Param("limit") int limit);

    // Atomic compare-and-set on the status column; returns 0 when the campaign is not in an expected status.
    // This is what prevents a campaign from being dispatched twice (manual send racing the scheduler,
    // or several application instances running the scheduler).
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update Campaign c set c.status = :newStatus, c.updatedAt = :now
            where c.id = :id and c.status in :expectedStatuses
            """)
    int transitionStatus(@Param("id") Long id,
                         @Param("expectedStatuses") Collection<CampaignStatus> expectedStatuses,
                         @Param("newStatus") CampaignStatus newStatus,
                         @Param("now") LocalDateTime now);
}
