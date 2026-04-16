package com.emailagent.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ForwardingRuleRequest {
    @NotBlank
    private String name;

    private String[] keywords = new String[0];
    private String[] negativeKeywords = new String[0];

    @NotBlank
    private String recipientEmail;

    private String conditions;
    private boolean active = true;
    private int priority = 0;
    private String senderPattern;
    private String subjectPattern;
    private boolean aiEnabled = true;
    private String aiContext;
    private boolean rotationEnabled = false;
    private boolean smartThreadEnabled = true;
    private boolean extractAttachments = false;
    private UUID templateId;
}
