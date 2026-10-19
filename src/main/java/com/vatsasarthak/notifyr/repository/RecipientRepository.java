package com.vatsasarthak.notifyr.repository;


import com.vatsasarthak.notifyr.entity.Recipient;
import com.vatsasarthak.notifyr.entity.RecipientStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RecipientRepository extends JpaRepository<Recipient, Long> {

    Optional<Recipient> findByEmail(String email);

    @Query("SELECT r FROM Recipient r WHERE " +
    "LOWER(r.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
    "LOWER(r.email) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    Page<Recipient> searchByNameOrEmail(@Param("keyword") String keyword, Pageable pageable);

    List<Recipient> findByStatusNot(RecipientStatus status);

}
