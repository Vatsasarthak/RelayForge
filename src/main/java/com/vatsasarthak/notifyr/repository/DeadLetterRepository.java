package com.vatsasarthak.notifyr.repository;

import com.vatsasarthak.notifyr.entity.DeadLetter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeadLetterRepository extends JpaRepository<DeadLetter, Long> {

    List<DeadLetter> findByNotificationId(Long notificationId);
}
