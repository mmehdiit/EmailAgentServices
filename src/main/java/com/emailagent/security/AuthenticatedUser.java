package com.emailagent.security;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.UUID;

@Data
@AllArgsConstructor
public class AuthenticatedUser {
    private UUID id;
    private String email;
}
