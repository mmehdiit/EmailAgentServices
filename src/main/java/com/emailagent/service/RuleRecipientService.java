package com.emailagent.service;

import com.emailagent.dto.RuleRecipientDto;
import com.emailagent.dto.RuleRecipientRequest;
import com.emailagent.exception.ApiException;
import com.emailagent.model.RuleRecipient;
import com.emailagent.repository.ForwardingRuleRepository;
import com.emailagent.repository.RuleRecipientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RuleRecipientService {

    private final RuleRecipientRepository recipientRepository;
    private final ForwardingRuleRepository ruleRepository;

    public List<RuleRecipientDto> getRecipientsForRule(UUID userId, UUID ruleId) {
        // Verify rule belongs to user
        ruleRepository.findByIdAndUserId(ruleId, userId)
                .orElseThrow(() -> ApiException.notFound("Rule not found"));
        return recipientRepository.findByRuleIdOrderBySortOrderAsc(ruleId)
                .stream().map(this::toDto).toList();
    }

    @Transactional
    public RuleRecipientDto addRecipient(UUID userId, UUID ruleId, RuleRecipientRequest request) {
        ruleRepository.findByIdAndUserId(ruleId, userId)
                .orElseThrow(() -> ApiException.notFound("Rule not found"));

        RuleRecipient recipient = new RuleRecipient();
        recipient.setRuleId(ruleId);
        applyRequest(recipient, request);
        return toDto(recipientRepository.save(recipient));
    }

    @Transactional
    public RuleRecipientDto updateRecipient(UUID userId, UUID ruleId, UUID recipientId, RuleRecipientRequest request) {
        ruleRepository.findByIdAndUserId(ruleId, userId)
                .orElseThrow(() -> ApiException.notFound("Rule not found"));

        RuleRecipient recipient = recipientRepository.findByIdAndRuleId(recipientId, ruleId)
                .orElseThrow(() -> ApiException.notFound("Recipient not found"));

        applyRequest(recipient, request);
        return toDto(recipientRepository.save(recipient));
    }

    @Transactional
    public void deleteRecipient(UUID userId, UUID ruleId, UUID recipientId) {
        ruleRepository.findByIdAndUserId(ruleId, userId)
                .orElseThrow(() -> ApiException.notFound("Rule not found"));

        RuleRecipient recipient = recipientRepository.findByIdAndRuleId(recipientId, ruleId)
                .orElseThrow(() -> ApiException.notFound("Recipient not found"));

        recipientRepository.delete(recipient);
    }

    @Transactional
    public void syncRecipients(UUID userId, UUID ruleId, List<RuleRecipientRequest> recipients) {
        ruleRepository.findByIdAndUserId(ruleId, userId)
                .orElseThrow(() -> ApiException.notFound("Rule not found"));

        recipientRepository.deleteByRuleId(ruleId);

        for (int i = 0; i < recipients.size(); i++) {
            RuleRecipient r = new RuleRecipient();
            r.setRuleId(ruleId);
            r.setSortOrder(i);
            applyRequest(r, recipients.get(i));
            recipientRepository.save(r);
        }
    }

    private void applyRequest(RuleRecipient recipient, RuleRecipientRequest req) {
        recipient.setEmail(req.getEmail());
        recipient.setDisplayName(req.getDisplayName());
        recipient.setSortOrder(req.getSortOrder());
        recipient.setOnVacation(req.isOnVacation()); // Lombok: isOnVacation field → isOnVacation() getter
        recipient.setVacationStart(req.getVacationStart());
        recipient.setVacationEnd(req.getVacationEnd());
    }

    public RuleRecipientDto toDto(RuleRecipient r) {
        RuleRecipientDto dto = new RuleRecipientDto();
        dto.setId(r.getId());
        dto.setRuleId(r.getRuleId());
        dto.setEmail(r.getEmail());
        dto.setDisplayName(r.getDisplayName());
        dto.setSortOrder(r.getSortOrder());
        dto.setOnVacation(r.isOnVacation());
        dto.setVacationStart(r.getVacationStart());
        dto.setVacationEnd(r.getVacationEnd());
        dto.setCreatedAt(r.getCreatedAt());
        return dto;
    }
}
