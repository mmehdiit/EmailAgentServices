package com.emailagent.controller;

import com.emailagent.dto.EmailLogDto;
import com.emailagent.security.AuthenticatedUser;
import com.emailagent.service.EmailLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/api/email-agent/email-logs")
@RequiredArgsConstructor
public class EmailLogController {

    private final EmailLogService emailLogService;

    @GetMapping
    public ResponseEntity<List<EmailLogDto>> getLogs(@AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.ok(emailLogService.getLogsForUser(user.getId()));
    }
}
