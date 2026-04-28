package com.emailagent.controller;

import com.emailagent.dto.CreateUserRequest;
import com.emailagent.dto.LoginResponse;
import com.emailagent.security.AuthenticatedUser;
import com.emailagent.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/api/email-agent/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AuthService authService;

    @PostMapping("/users")
    public ResponseEntity<LoginResponse> createUser(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.ok(authService.createUser(request, caller.getId()));
    }
}
