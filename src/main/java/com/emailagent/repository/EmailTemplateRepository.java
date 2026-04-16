package com.emailagent.repository;

import com.emailagent.model.EmailTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailTemplateRepository extends JpaRepository<EmailTemplate, UUID> {
    List<EmailTemplate> findByUserId(UUID userId);
    Optional<EmailTemplate> findByIdAndUserId(UUID id, UUID userId);
}
