package com.emailagent.repository;

import com.emailagent.model.ForwardingRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ForwardingRuleRepository extends JpaRepository<ForwardingRule, UUID> {

    List<ForwardingRule> findByUserIdOrderByPriorityAscCreatedAtDesc(UUID userId);

    List<ForwardingRule> findByUserIdAndActiveTrueOrderByPriorityAscCreatedAtDesc(UUID userId);

    Optional<ForwardingRule> findByIdAndUserId(UUID id, UUID userId);

    @Modifying
    @Transactional
    @Query("UPDATE ForwardingRule r SET r.currentRotationIndex = :newIndex WHERE r.id = :ruleId")
    void updateRotationIndex(UUID ruleId, int newIndex);
}
