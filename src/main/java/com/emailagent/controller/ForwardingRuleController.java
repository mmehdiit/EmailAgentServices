package com.emailagent.controller;

import com.emailagent.dto.ForwardingRuleDto;
import com.emailagent.dto.ForwardingRuleRequest;
import com.emailagent.security.AuthenticatedUser;
import com.emailagent.service.ForwardingRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/api/rules")
@RequiredArgsConstructor
public class ForwardingRuleController {

    private final ForwardingRuleService ruleService;

    @GetMapping
    public ResponseEntity<List<ForwardingRuleDto>> getRules(@AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.ok(ruleService.getRules(user.getId()));
    }

    @PostMapping
    public ResponseEntity<ForwardingRuleDto> createRule(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ForwardingRuleRequest request) {
        return ResponseEntity.ok(ruleService.createRule(user.getId(), request));
    }

    @PutMapping("/{ruleId}")
    public ResponseEntity<ForwardingRuleDto> updateRule(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID ruleId,
            @Valid @RequestBody ForwardingRuleRequest request) {
        return ResponseEntity.ok(ruleService.updateRule(user.getId(), ruleId, request));
    }

    @DeleteMapping("/{ruleId}")
    public ResponseEntity<Map<String, Boolean>> deleteRule(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID ruleId) {
        ruleService.deleteRule(user.getId(), ruleId);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/reorder")
    public ResponseEntity<Map<String, Boolean>> reorderRules(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody Map<String, List<UUID>> body) {
        ruleService.updatePriorities(user.getId(), body.get("ids"));
        return ResponseEntity.ok(Map.of("success", true));
    }
}
