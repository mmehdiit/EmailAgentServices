package com.emailagent.dto;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class ForwardingRuleDto {
    private UUID id;
    private UUID userId;
    private String name;
    private String[] keywords;
    private String[] negativeKeywords;
    private String recipientEmail;
    private String conditions;
    private boolean active;
    private int priority;
    private String senderPattern;
    private String subjectPattern;
    private boolean aiEnabled;
    private String aiContext;
    private boolean rotationEnabled;
    private int currentRotationIndex;
    private boolean smartThreadEnabled;
    private boolean extractAttachments;
    private UUID templateId;
    private OffsetDateTime createdAt;
}
