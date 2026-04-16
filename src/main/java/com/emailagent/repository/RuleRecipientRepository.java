package com.emailagent.repository;

import com.emailagent.model.RuleRecipient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RuleRecipientRepository extends JpaRepository<RuleRecipient, UUID> {

    List<RuleRecipient> findByRuleIdOrderBySortOrderAsc(UUID ruleId);

    List<RuleRecipient> findByRuleIdInOrderBySortOrderAsc(List<UUID> ruleIds);

    Optional<RuleRecipient> findByIdAndRuleId(UUID id, UUID ruleId);

    void deleteByRuleId(UUID ruleId);
}
