package com.emailagent.controller;

import com.emailagent.dto.LoginResponse;
import com.emailagent.dto.MicrosoftLoginRequest;
import com.emailagent.security.AuthenticatedUser;
import com.emailagent.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v1/api/email-agent/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/microsoft-login")
    public ResponseEntity<LoginResponse> microsoftLogin(@Valid @RequestBody MicrosoftLoginRequest request) {
        return ResponseEntity.ok(authService.microsoftLogin(request.getIdToken()));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(@AuthenticationPrincipal AuthenticatedUser user) {
        String role = authService.getUserRole(user.getId());
        return ResponseEntity.ok(Map.of(
                "id", user.getId(),
                "email", user.getEmail(),
                "role", role
        ));
    }
}
