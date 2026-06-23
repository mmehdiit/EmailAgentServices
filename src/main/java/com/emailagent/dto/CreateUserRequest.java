package com.emailagent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.UUID;

@Data
public class CreateUserRequest {
    @NotBlank
    @Email
    private String email;

    private String role = "user";

    @JsonProperty("departmentId")
    private UUID departmentId;
}
