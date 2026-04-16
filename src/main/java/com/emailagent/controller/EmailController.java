package com.emailagent.controller;

import com.emailagent.exception.ApiException;
import com.emailagent.model.ForwardingRule;
import com.emailagent.repository.ForwardingRuleRepository;
import com.emailagent.security.AuthenticatedUser;
import com.emailagent.service.EmailClassificationService;
import com.emailagent.service.EmailProcessingService;
import com.emailagent.service.OutlookService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/v1/api/emails")
@RequiredArgsConstructor
public class EmailController {

    private final EmailProcessingService emailProcessingService;
    private final OutlookService outlookService;
    private final EmailClassificationService classificationService;
    private final ForwardingRuleRepository ruleRepository;

    @PostMapping("/process")
    public ResponseEntity<Map<String, Object>> processEmails(@AuthenticationPrincipal AuthenticatedUser user) {
        Map<String, Object> result = emailProcessingService.processForUser(user.getId());
        return ResponseEntity.ok(result);
    }
    
    @GetMapping("/unread")
    public ResponseEntity<List<Map<String, Object>>> getUnreadEmails(@AuthenticationPrincipal AuthenticatedUser user) {
        String accessToken = outlookService.getValidAccessToken(user.getId());
        JsonNode emailsData = outlookService.callGraphApi(accessToken,
                "mailFolders/inbox/messages?$filter=isRead eq false&$top=50" +
                        "&$orderby=receivedDateTime desc" +
                        "&$select=id,subject,from,receivedDateTime,bodyPreview,conversationId,hasAttachments");

        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode email : emailsData.path("value")) {
            result.add(Map.of(
                    "id", email.path("id").asText(),
                    "subject", email.path("subject").asText(""),
                    "from", email.path("from").path("emailAddress").path("address").asText(""),
                    "fromName", email.path("from").path("emailAddress").path("name").asText(""),
                    "receivedDateTime", email.path("receivedDateTime").asText(""),
                    "bodyPreview", email.path("bodyPreview").asText(""),
                    "conversationId", email.path("conversationId").asText(""),
                    "hasAttachments", email.path("hasAttachments").asBoolean(false)
            ));
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{messageId}/content")
    public ResponseEntity<Map<String, Object>> getEmailContent(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String messageId) {
        String accessToken = outlookService.getValidAccessToken(user.getId());
        JsonNode email = outlookService.callGraphApi(accessToken,
                "messages/" + messageId + "?$select=id,subject,from,body,receivedDateTime,conversationId");

        String htmlBody = email.path("body").path("content").asText("");
        String plainText = EmailProcessingService.extractTextFromHtml(htmlBody);

        return ResponseEntity.ok(Map.of(
                "id", email.path("id").asText(),
                "subject", email.path("subject").asText(""),
                "from", email.path("from").path("emailAddress").path("address").asText(""),
                "body", plainText,
                "htmlBody", htmlBody,
                "receivedDateTime", email.path("receivedDateTime").asText("")
        ));
    }

    @PatchMapping("/{messageId}/read")
    public ResponseEntity<Map<String, Boolean>> markAsRead(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String messageId) {
        String accessToken = outlookService.getValidAccessToken(user.getId());
        outlookService.patchMessage(accessToken, messageId, "{\"isRead\": true}");
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PatchMapping("/{messageId}/unread")
    public ResponseEntity<Map<String, Boolean>> markAsUnread(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String messageId) {
        String accessToken = outlookService.getValidAccessToken(user.getId());
        outlookService.patchMessage(accessToken, messageId, "{\"isRead\": false}");
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/manual-assign")
    public ResponseEntity<Map<String, Object>> manualAssign(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody Map<String, String> body) {
        String emailId = body.get("emailId");
        String ruleIdStr = body.get("ruleId");

        if (emailId == null || ruleIdStr == null) {
            throw ApiException.badRequest("emailId and ruleId are required");
        }

        emailProcessingService.manualAssign(user.getId(), emailId, UUID.fromString(ruleIdStr));
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/retry-failed")
    public ResponseEntity<Map<String, Object>> retryFailed(@AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.ok(emailProcessingService.retryFailedEmails(user.getId()));
    }

    @PostMapping("/classify")
    public ResponseEntity<Map<String, Object>> classifyEmail(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, String> emailMap = (Map<String, String>) body.get("email");
        List<ForwardingRule> rules = ruleRepository.findByUserIdAndActiveTrueOrderByPriorityAscCreatedAtDesc(user.getId());

        EmailClassificationService.EmailData emailData = new EmailClassificationService.EmailData(
                emailMap.getOrDefault("subject", ""),
                emailMap.getOrDefault("body", ""),
                emailMap.getOrDefault("sender", ""),
                false, null, null, null
        );

        EmailClassificationService.ClassificationResult result = classificationService.classify(emailData, rules);
        return ResponseEntity.ok(Map.of(
                "matched_rule_id", result.matchedRuleId() != null ? result.matchedRuleId() : "",
                "matched_rule_name", result.matchedRuleName() != null ? result.matchedRuleName() : "",
                "confidence", result.confidence(),
                "reasoning", result.reasoning() != null ? result.reasoning() : "",
                "override_recipient_email", result.overrideRecipientEmail() != null ? result.overrideRecipientEmail() : ""
        ));
    }
}
