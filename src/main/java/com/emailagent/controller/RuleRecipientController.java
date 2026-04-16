package com.emailagent.controller;

import com.emailagent.dto.RuleRecipientDto;
import com.emailagent.dto.RuleRecipientRequest;
import com.emailagent.security.AuthenticatedUser;
import com.emailagent.service.RuleRecipientService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/api/rules/{ruleId}/recipients")
@RequiredArgsConstructor
public class RuleRecipientController {

    private final RuleRecipientService recipientService;

    @GetMapping
    public ResponseEntity<List<RuleRecipientDto>> getRecipients(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID ruleId) {
        return ResponseEntity.ok(recipientService.getRecipientsForRule(user.getId(), ruleId));
    }

    @PostMapping
    public ResponseEntity<RuleRecipientDto> addRecipient(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID ruleId,
            @Valid @RequestBody RuleRecipientRequest request) {
        return ResponseEntity.ok(recipientService.addRecipient(user.getId(), ruleId, request));
    }

    @PutMapping("/{recipientId}")
    public ResponseEntity<RuleRecipientDto> updateRecipient(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID ruleId,
            @PathVariable UUID recipientId,
            @Valid @RequestBody RuleRecipientRequest request) {
        return ResponseEntity.ok(recipientService.updateRecipient(user.getId(), ruleId, recipientId, request));
    }

    @DeleteMapping("/{recipientId}")
    public ResponseEntity<Map<String, Boolean>> deleteRecipient(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID ruleId,
            @PathVariable UUID recipientId) {
        recipientService.deleteRecipient(user.getId(), ruleId, recipientId);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/sync")
    public ResponseEntity<Map<String, Boolean>> syncRecipients(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID ruleId,
            @RequestBody List<RuleRecipientRequest> recipients) {
        recipientService.syncRecipients(user.getId(), ruleId, recipients);
        return ResponseEntity.ok(Map.of("success", true));
    }
}
