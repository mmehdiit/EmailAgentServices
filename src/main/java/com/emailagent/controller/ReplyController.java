package com.emailagent.controller;

import com.emailagent.security.AuthenticatedUser;
import com.emailagent.service.ReplyCheckingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/api/reply")
@RequiredArgsConstructor
public class ReplyController {

    private final ReplyCheckingService replyCheckingService;

    /**
     * Mark reply via tracking link - returns HTML if browser request, JSON otherwise.
     */
    @GetMapping(value = "/mark", produces = {MediaType.TEXT_HTML_VALUE, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<?> markReplyGet(
            @RequestParam String token,
            @RequestParam(defaultValue = "false") boolean preview,
            @RequestHeader(value = "Accept", defaultValue = "") String acceptHeader) {

        UUID trackingToken;
        try {
            trackingToken = UUID.fromString(token);
        } catch (IllegalArgumentException e) {
            return buildResponse(false, "Invalid tracking token", null, acceptHeader);
        }

        Object[] result = new Object[]{null, null};
        replyCheckingService.markReply(trackingToken, preview, result);

        String status = (String) result[0];
        String subject = (String) result[1];

        return switch (status) {
            case "not_found" -> buildResponse(false, "Invalid or expired tracking link", null, acceptHeader);
            case "pending" -> ResponseEntity.ok(Map.of("status", "pending", "email_subject", subject != null ? subject : ""));
            case "already_replied" -> buildResponse(true, "This email was already marked as replied", subject, acceptHeader);
            case "success" -> buildResponse(true, "Reply successfully recorded!", subject, acceptHeader);
            default -> buildResponse(false, "Unknown error", null, acceptHeader);
        };
    }

    @PostMapping("/mark")
    public ResponseEntity<Map<String, Object>> markReplyPost(@RequestBody Map<String, Object> body) {
        String tokenStr = (String) body.get("token");
        boolean preview = Boolean.TRUE.equals(body.get("preview"));

        UUID trackingToken;
        try {
            trackingToken = UUID.fromString(tokenStr);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid tracking token"));
        }

        Object[] result = new Object[]{null, null};
        replyCheckingService.markReply(trackingToken, preview, result);

        String status = (String) result[0];
        String subject = (String) result[1];

        return ResponseEntity.ok(Map.of(
                "status", status != null ? status : "error",
                "email_subject", subject != null ? subject : ""
        ));
    }

    @PostMapping("/mark-manual")
    public ResponseEntity<Map<String, Boolean>> markReplyManual(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody Map<String, String> body) {
        UUID emailLogId = UUID.fromString(body.get("emailLogId"));
        replyCheckingService.markReplyManual(emailLogId, user.getId());
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/check")
    public ResponseEntity<Map<String, Boolean>> triggerCheck(@AuthenticationPrincipal AuthenticatedUser user) {
        replyCheckingService.checkRepliesForAllUsers();
        return ResponseEntity.ok(Map.of("success", true));
    }

    private ResponseEntity<?> buildResponse(boolean success, String message, String emailSubject, String acceptHeader) {
        if (acceptHeader.contains("text/html")) {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .body(generateHtml(success, message, emailSubject));
        }
        return ResponseEntity.ok(Map.of(
                "status", success ? "success" : "error",
                "message", message,
                "email_subject", emailSubject != null ? emailSubject : ""
        ));
    }

    private String generateHtml(boolean success, String message, String emailSubject) {
        String bgColor = success ? "#10b981" : "#ef4444";
        String icon = success ? "✓" : "✗";
        String subjectHtml = emailSubject != null ? "<div class=\"email-subject\">📧 " + emailSubject + "</div>" : "";

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>Reply Tracking - AI Email Agent</title>
              <style>
                * { margin: 0; padding: 0; box-sizing: border-box; }
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                  min-height: 100vh; display: flex; align-items: center; justify-content: center;
                  background: linear-gradient(135deg, #0C799A 0%%, #065666 100%%); padding: 20px; }
                .container { background: white; border-radius: 16px; padding: 40px; max-width: 480px;
                  width: 100%%; text-align: center; box-shadow: 0 25px 50px -12px rgba(0,0,0,0.25); }
                .icon { width: 80px; height: 80px; border-radius: 50%%; background: %s; color: white;
                  font-size: 40px; display: flex; align-items: center; justify-content: center; margin: 0 auto 24px; }
                h1 { font-size: 24px; color: #1a1a1a; margin-bottom: 12px; }
                .message { font-size: 16px; color: #666; margin-bottom: 20px; line-height: 1.5; }
                .email-subject { font-size: 14px; color: #888; padding: 12px 16px; background: #f5f5f5;
                  border-radius: 8px; margin-top: 16px; word-break: break-word; }
                .logo { margin-top: 32px; color: #0C799A; font-weight: 600; font-size: 14px; }
              </style>
            </head>
            <body>
              <div class="container">
                <div class="icon">%s</div>
                <h1>%s</h1>
                <p class="message">%s</p>
                %s
                <div class="logo">AI Email Agent by Tecram</div>
              </div>
            </body>
            </html>
            """.formatted(bgColor, icon, success ? "Reply Recorded" : "Error", message, subjectHtml);
    }
}
