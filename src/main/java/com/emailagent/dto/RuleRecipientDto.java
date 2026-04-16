package com.emailagent.dto;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class RuleRecipientDto {
    private UUID id;
    private UUID ruleId;
    private String email;
    private String displayName;
    private int sortOrder;
    private boolean onVacation;     // serializes as "on_vacation" via SNAKE_CASE config
    private OffsetDateTime vacationStart;
    private OffsetDateTime vacationEnd;
    private OffsetDateTime createdAt;
}
