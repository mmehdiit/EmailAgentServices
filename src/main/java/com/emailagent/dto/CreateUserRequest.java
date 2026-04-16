package com.emailagent.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateUserRequest {
    @NotBlank
    @Email
    private String email;

    @NotBlank
    @Size(min = 6)
    private String password;

    private String role = "user";
}
