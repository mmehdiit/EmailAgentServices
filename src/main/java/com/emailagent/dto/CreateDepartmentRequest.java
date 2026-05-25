package com.emailagent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateDepartmentRequest {
    @NotBlank
    private String name;
}
