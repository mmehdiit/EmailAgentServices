package com.emailagent.dto;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class EmailLogDto {
    private UUID id;
    private UUID userId;
    private String emailFrom;
    private String emailSubject;
    private String forwardedTo;
    private UUID ruleMatched;
    private String status;
    private String outlookMessageId;
    private String outlookConversationId;
    private OffsetDateTime processedAt;
    private OffsetDateTime repliedAt;
    private boolean replyDetected;
    private boolean aiClassified;
    private Double aiConfidence;
    private String aiReasoning;
    private UUID trackingToken;
    private String replySource;
    private OffsetDateTime receivedAt;
    private String negativeKeywordOverride;
    private String negativeKeywordOverrideLog;
}
