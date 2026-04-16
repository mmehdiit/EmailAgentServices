package com.emailagent.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class RuleRecipientRequest {
    @NotBlank
    @Email
    private String email;

    private String displayName;
    private int sortOrder = 0;
    private boolean onVacation = false;   // accepted as "on_vacation" via SNAKE_CASE config
    private OffsetDateTime vacationStart;
    private OffsetDateTime vacationEnd;
}
