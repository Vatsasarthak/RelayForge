package com.vatsasarthak.notifyr.repository;

import com.vatsasarthak.notifyr.entity.Template;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TemplateRepository extends JpaRepository<Template, Long> {

    Optional<Template> findByName(String name);
}
