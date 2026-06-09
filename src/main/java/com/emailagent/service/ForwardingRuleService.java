package com.emailagent.service;

import com.emailagent.dto.ForwardingRuleDto;
import com.emailagent.dto.ForwardingRuleRequest;
import com.emailagent.exception.ApiException;
import com.emailagent.model.ForwardingRule;
import com.emailagent.model.User;
import com.emailagent.repository.ForwardingRuleRepository;
import com.emailagent.repository.UserRepository;
import com.emailagent.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ForwardingRuleService {

    private final ForwardingRuleRepository ruleRepository;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;

    public List<ForwardingRuleDto> getRules(UUID userId) {
        UUID effectiveUserId = resolveEffectiveUserId(userId);
        return ruleRepository.findByUserIdOrderByPriorityAscCreatedAtDesc(effectiveUserId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    private UUID resolveEffectiveUserId(UUID userId) {
        String role = userRoleRepository.findByUserId(userId)
                .map(ur -> ur.getRole())
                .orElse("user");
        if (!"user".equals(role)) {
            return userId;
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getDepartmentId() == null) {
            throw new RuntimeException("User is not assigned to a department");
        }
        return userRepository.findAdminByDepartmentId(user.getDepartmentId())
                .map(User::getId)
                .orElseThrow(() -> new RuntimeException("No admin found for department"));
    }

    @Transactional
    public ForwardingRuleDto createRule(UUID userId, ForwardingRuleRequest request) {
        ForwardingRule rule = new ForwardingRule();
        applyRequest(rule, request);
        rule.setUserId(userId);
        return toDto(ruleRepository.save(rule));
    }

    @Transactional
    public ForwardingRuleDto updateRule(UUID userId, UUID ruleId, ForwardingRuleRequest request) {
        ForwardingRule rule = ruleRepository.findByIdAndUserId(ruleId, userId)
                .orElseThrow(() -> ApiException.notFound("Rule not found"));
        applyRequest(rule, request);
        return toDto(ruleRepository.save(rule));
    }

    @Transactional
    public void deleteRule(UUID userId, UUID ruleId) {
        ForwardingRule rule = ruleRepository.findByIdAndUserId(ruleId, userId)
                .orElseThrow(() -> ApiException.notFound("Rule not found"));
        ruleRepository.delete(rule);
    }

    @Transactional
    public void updatePriorities(UUID userId, List<UUID> orderedIds) {
        for (int i = 0; i < orderedIds.size(); i++) {
            UUID ruleId = orderedIds.get(i);
            ForwardingRule rule = ruleRepository.findByIdAndUserId(ruleId, userId)
                    .orElseThrow(() -> ApiException.notFound("Rule not found: " + ruleId));
            rule.setPriority(i);
            ruleRepository.save(rule);
        }
    }

    private void applyRequest(ForwardingRule rule, ForwardingRuleRequest req) {
        rule.setName(req.getName());
        rule.setKeywords(req.getKeywords() != null ? req.getKeywords() : new String[0]);
        rule.setNegativeKeywords(req.getNegativeKeywords() != null ? req.getNegativeKeywords() : new String[0]);
        rule.setRecipientEmail(req.getRecipientEmail());
        rule.setConditions(req.getConditions());
        rule.setActive(req.isActive());
        rule.setPriority(req.getPriority());
        rule.setSenderPattern(req.getSenderPattern());
        rule.setSubjectPattern(req.getSubjectPattern());
        rule.setAiEnabled(req.isAiEnabled());
        rule.setAiContext(req.getAiContext());
        rule.setRotationEnabled(req.isRotationEnabled());
        rule.setSmartThreadEnabled(req.isSmartThreadEnabled());
        rule.setExtractAttachments(req.isExtractAttachments());
        rule.setTemplateId(req.getTemplateId());
    }

    public ForwardingRuleDto toDto(ForwardingRule rule) {
        ForwardingRuleDto dto = new ForwardingRuleDto();
        dto.setId(rule.getId());
        dto.setUserId(rule.getUserId());
        dto.setName(rule.getName());
        dto.setKeywords(rule.getKeywords());
        dto.setNegativeKeywords(rule.getNegativeKeywords());
        dto.setRecipientEmail(rule.getRecipientEmail());
        dto.setConditions(rule.getConditions());
        dto.setActive(rule.isActive());
        dto.setPriority(rule.getPriority());
        dto.setSenderPattern(rule.getSenderPattern());
        dto.setSubjectPattern(rule.getSubjectPattern());
        dto.setAiEnabled(rule.isAiEnabled());
        dto.setAiContext(rule.getAiContext());
        dto.setRotationEnabled(rule.isRotationEnabled());
        dto.setCurrentRotationIndex(rule.getCurrentRotationIndex());
        dto.setSmartThreadEnabled(rule.isSmartThreadEnabled());
        dto.setExtractAttachments(rule.isExtractAttachments());
        dto.setTemplateId(rule.getTemplateId());
        dto.setCreatedAt(rule.getCreatedAt());
        return dto;
    }
}
