package com.emailagent.service;

import com.emailagent.dto.EmailLogDto;
import com.emailagent.model.EmailLog;
import com.emailagent.model.User;
import com.emailagent.repository.EmailLogRepository;
import com.emailagent.repository.UserRepository;
import com.emailagent.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EmailLogService {

    private final EmailLogRepository emailLogRepository;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;

    public List<EmailLogDto> getLogsForUser(UUID userId, LocalDate fromDate, LocalDate toDate) {
        UUID effectiveUserId = resolveEffectiveUserId(userId);
        if (fromDate != null && toDate != null) {
            OffsetDateTime from = fromDate.atStartOfDay().atOffset(ZoneOffset.UTC);
            OffsetDateTime to = toDate.atTime(23, 59, 59).atOffset(ZoneOffset.UTC);
            return emailLogRepository.findByUserIdAndProcessedAtBetweenOrderByProcessedAtDesc(effectiveUserId, from, to)
                    .stream()
                    .map(this::toDto)
                    .toList();
        }
        return emailLogRepository.findByUserIdOrderByProcessedAtDesc(effectiveUserId)
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

    public EmailLogDto toDto(EmailLog log) {
        EmailLogDto dto = new EmailLogDto();
        dto.setId(log.getId());
        dto.setUserId(log.getUserId());
        dto.setEmailFrom(log.getEmailFrom());
        dto.setEmailSubject(log.getEmailSubject());
        dto.setForwardedTo(log.getForwardedTo());
        dto.setRuleMatched(log.getRuleMatched());
        dto.setStatus(log.getStatus());
        dto.setOutlookMessageId(log.getOutlookMessageId());
        dto.setOutlookConversationId(log.getOutlookConversationId());
        dto.setProcessedAt(log.getProcessedAt());
        dto.setRepliedAt(log.getRepliedAt());
        dto.setReplyDetected(log.isReplyDetected());
        dto.setAiClassified(log.isAiClassified());
        dto.setAiConfidence(log.getAiConfidence());
        dto.setAiReasoning(log.getAiReasoning());
        dto.setTrackingToken(log.getTrackingToken());
        dto.setReplySource(log.getReplySource());
        dto.setReceivedAt(log.getReceivedAt());
        dto.setNegativeKeywordOverride(log.getNegativeKeywordOverride());
        dto.setNegativeKeywordOverrideLog(log.getNegativeKeywordOverrideLog());
        return dto;
    }
}
