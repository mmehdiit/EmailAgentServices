package com.emailagent.controller;

import com.emailagent.dto.OutlookCallbackRequest;
import com.emailagent.exception.ApiException;
import com.emailagent.model.OutlookConnection;
import com.emailagent.security.AuthenticatedUser;
import com.emailagent.service.AuthService;
import com.emailagent.service.OutlookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/v1/api/email-agent/outlook")
@RequiredArgsConstructor
public class OutlookController {

    private final OutlookService outlookService;
    private final AuthService authService;

    @PostMapping("/auth")
    public ResponseEntity<Map<String, String>> getAuthUrl(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody Map<String, String> body) {
        if (!"admin".equals(authService.getUserRole(user.getId()))) {
            throw ApiException.forbidden("Only admin users can connect to an Outlook account");
        }
        String authUrl = outlookService.buildAuthUrl(body.get("frontendOrigin"), body.get("redirectUri"));
        return ResponseEntity.ok(Map.of("authUrl", authUrl));
    }

    @PostMapping("/callback")
    public ResponseEntity<Map<String, Object>> handleCallback(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody OutlookCallbackRequest request) {
        if (!"admin".equals(authService.getUserRole(user.getId()))) {
            throw ApiException.forbidden("Only admin users can connect to an Outlook account");
        }
        String email = outlookService.exchangeCodeForTokens(
                user.getId(), request.getCode(), request.getFrontendOrigin(), request.getRedirectUri());
        return ResponseEntity.ok(Map.of("success", true, "email", email));
    }

    @GetMapping("/connection")
    public ResponseEntity<Map<String, Object>> getConnection(
            @AuthenticationPrincipal AuthenticatedUser user) {
        Optional<OutlookConnection> connection = outlookService.getConnection(user.getId());
        if (connection.isPresent()) {
            return ResponseEntity.ok(Map.of(
                    "connected", true,
                    "email", connection.get().getEmailAddress() != null ? connection.get().getEmailAddress() : ""
            ));
        }
        return ResponseEntity.ok(Map.of("connected", false));
    }

    @DeleteMapping("/connection")
    public ResponseEntity<Map<String, Object>> disconnect(
            @AuthenticationPrincipal AuthenticatedUser user) {
        outlookService.disconnectOutlook(user.getId());
        return ResponseEntity.ok(Map.of("success", true));
    }
}
