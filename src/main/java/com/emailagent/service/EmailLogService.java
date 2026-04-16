package com.emailagent.service;

import com.emailagent.dto.EmailLogDto;
import com.emailagent.model.EmailLog;
import com.emailagent.repository.EmailLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EmailLogService {

    private final EmailLogRepository emailLogRepository;

    public List<EmailLogDto> getLogsForUser(UUID userId) {
        return emailLogRepository.findByUserIdOrderByProcessedAtDesc(userId)
                .stream()
                .map(this::toDto)
                .toList();
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
        return dto;
    }
}
