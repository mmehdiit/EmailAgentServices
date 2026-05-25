package com.emailagent.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class AssignDepartmentRequest {
    @NotNull
    private UUID departmentId;
}
