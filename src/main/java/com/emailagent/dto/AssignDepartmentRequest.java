package com.emailagent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class AssignDepartmentRequest {
    @NotNull
    @JsonProperty("departmentId")
    private UUID departmentId;
}
