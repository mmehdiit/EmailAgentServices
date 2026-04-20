package com.emailagent.service;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import com.emailagent.dto.notification.NewNotificationByReportResponse;
import com.emailagent.dto.policereport.PoliceReportDto;
import com.emailagent.model.EmailLog;
import com.emailagent.model.ForwardingRule;
import com.emailagent.model.OutlookConnection;
import com.emailagent.model.RuleRecipient;
import com.emailagent.repository.EmailLogRepository;
import com.emailagent.repository.ForwardingRuleRepository;
import com.emailagent.repository.OutlookConnectionRepository;
import com.emailagent.repository.RuleRecipientRepository;
import com.emailagent.service.EmailClassificationService.ClassificationResult;
import com.emailagent.service.EmailClassificationService.EmailData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailProcessingService {

    private final OutlookConnectionRepository connectionRepository;
    private final ForwardingRuleRepository ruleRepository;
    private final RuleRecipientRepository recipientRepository;
    private final EmailLogRepository emailLogRepository;
    private final OutlookService outlookService;
    private final EmailClassificationService classificationService;
    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;

    @Value("${app.url}")
    private String appUrl;

    @Value("${app.processing.enabled:true}")
    private boolean processingEnabled;

    @Value("${app.ocr.url:http://localhost:8014}")
    private String ocrBaseUrl;

    @Value("${app.ocr.auth.url:http://localhost:8001}")
    private String ocrAuthUrl;

    @Value("${app.ocr.auth.username:}")
    private String ocrAuthUsername;

    @Value("${app.ocr.auth.password:}")
    private String ocrAuthPassword;

    @Value("${app.callcenter.url:http://localhost:8004}")
    private String callCenterBaseUrl;

    @Value("${app.datamanagement.url:http://localhost:8005}")
    private String dataManagementBaseUrl;

    private final RestTemplate ocrRestTemplate = new RestTemplate();
    private String cachedOcrToken;
    private long cachedOcrTokenFetchedAt = 0;

    /**
     * Scheduled job: process unread emails for all users every 5 minutes.
     */
    @Scheduled(cron = "${app.processing.cron:0 */5 * * * *}")
    public void scheduledProcessEmails() {
        if (!processingEnabled)
            return;
        log.info("Starting scheduled email processing...");
        processAllUsers();
    }

    @Transactional
    public Map<String, Object> processAllUsers() {
        List<OutlookConnection> connections = connectionRepository.findAll();
        if (connections.isEmpty()) {
            log.info("No Outlook connections found");
            return Map.of("message", "No connections to process");
        }

        int totalProcessed = 0, totalForwarded = 0, totalAiClassified = 0;

        for (OutlookConnection connection : connections) {
            try {
                Map<String, Integer> result = processUserEmails(connection);
                totalProcessed += result.getOrDefault("processed", 0);
                totalForwarded += result.getOrDefault("forwarded", 0);
                totalAiClassified += result.getOrDefault("ai_classified", 0);
            } catch (Exception e) {
                log.error("Error processing emails for user {}", connection.getUserId(), e);
            }
        }

        log.info("Email processing complete. Processed: {}, Forwarded: {}, AI: {}", totalProcessed, totalForwarded,
                totalAiClassified);
        return Map.of("processed", totalProcessed, "forwarded", totalForwarded, "ai_classified", totalAiClassified);
    }

    /**
     * Process emails for a specific user (can also be called on-demand from the
     * API).
     */
    @Transactional
    public Map<String, Object> processForUser(UUID userId) {
        OutlookConnection connection = connectionRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("No Outlook connection"));
        Map<String, Integer> result = processUserEmails(connection);
        return Map.of(
                "processed", result.getOrDefault("processed", 0),
                "forwarded", result.getOrDefault("forwarded", 0));
    }

    private Map<String, Integer> processUserEmails(OutlookConnection connection) {
        UUID userId = connection.getUserId();
        log.info("Processing emails for user {}", userId);

        if (connection.getAccessToken() == null || connection.getRefreshToken() == null) {
            log.error("Missing tokens for user {}", userId);
            return Map.of("processed", 0, "forwarded", 0, "ai_classified", 0);
        }

        // Refresh token if needed
        String accessToken;
        if (connection.getTokenExpiry().isBefore(OffsetDateTime.now().plusMinutes(5))) {
            accessToken = outlookService.refreshAccessToken(connection);
        } else {
            accessToken = connection.getAccessToken();
        }

        // Get active rules
        List<ForwardingRule> rules = ruleRepository.findByUserIdAndActiveTrueOrderByPriorityAscCreatedAtDesc(userId);
        if (rules.isEmpty()) {
            log.info("No active rules for user {}", userId);
            return Map.of("processed", 0, "forwarded", 0, "ai_classified", 0);
        }

        // Fetch unread emails
        JsonNode emailsData = outlookService.callGraphApi(accessToken,
                "mailFolders/inbox/messages?$filter=isRead eq false&$top=50&$orderby=receivedDateTime desc" +
                        "&$select=id,subject,from,receivedDateTime,body,bodyPreview,conversationId,toRecipients,ccRecipients,hasAttachments");

        JsonNode emails = emailsData.path("value");
        log.info("Found {} unread emails for user {}", emails.size(), userId);

        int processed = 0, forwarded = 0, aiClassified = 0;
        String integratedEmail = connection.getEmailAddress() != null ? connection.getEmailAddress().toLowerCase() : "";

        for (JsonNode email : emails) {
            try {
                String outlookMessageId = email.get("id").asText();
                String emailSubject = email.path("subject").asText("");
                String emailFrom = email.path("from").path("emailAddress").path("address").asText("");

                // Skip if already processed
                if (emailLogRepository.existsByOutlookMessageIdAndUserId(outlookMessageId, userId)) {
                    log.debug("[SKIP] Already processed: {}", emailSubject);
                    continue;
                }

                // CC detection: if integrated account is in CC, mark as read and skip
                JsonNode ccRecipients = email.path("ccRecipients");
                boolean isReplyWithCC = isInRecipients(ccRecipients, integratedEmail);
                if (isReplyWithCC && !integratedEmail.isEmpty()) {
                    log.info("[CC DETECTION] Skipping CC email: {}", emailSubject);
                    markEmailRead(accessToken, outlookMessageId);
                    // Update reply detection for this conversation
                    updateReplyViaCC(userId, email, integratedEmail);
                    continue;
                }

                processed++;

                // Extract email body as plain text
                String htmlBody = email.path("body").path("content").asText("");
                String bodyText = extractTextFromHtml(htmlBody);
                String conversationId = email.path("conversationId").asText(null);
                String receivedDateTime = email.path("receivedDateTime").asText(null);

                // Parse forwarded email headers
                ForwardedInfo forwardedInfo = parseForwardedHeaders(bodyText);

                EmailData emailData = new EmailData(
                        emailSubject, bodyText, emailFrom,
                        forwardedInfo.isForwarded(), forwardedInfo.originalSender(),
                        forwardedInfo.originalSubject(), forwardedInfo.originalDate());

                // OCR: skip emails whose attachments contain a police report
                boolean hasAttachments = email.path("hasAttachments").asBoolean(false);
                PoliceReportAttachment policeReportAttachment = null;
                if (hasAttachments
                        && (policeReportAttachment = hasPoliceReportAttachment(accessToken, outlookMessageId)) != null) {
                    log.info("[POLICE REPORT] Detected police report in email: {}", emailSubject);
                    try {
                        NewNotificationByReportResponse notificationResponse = createCarNotificationByPoliceReport(
                                policeReportAttachment.reportDto());
                        log.info("[POLICE REPORT] Successfully created car notification - ID: {}, Report: {}",
                                notificationResponse.getNotificationId(), notificationResponse.getReportNumber());

                        // If report number is empty, call the data management API
                        if (notificationResponse.getReportNumber() == null
                                || notificationResponse.getReportNumber().trim().isEmpty()) {
                            log.info("[POLICE REPORT] Report number is empty, calling data management API for carId: {}",
                                    notificationResponse.getCarId());
                            try {
                                processPoliceReportAutomation(notificationResponse.getCarId(),
                                        policeReportAttachment.fileBytes(), policeReportAttachment.fileName());
                                log.info("[POLICE REPORT] Successfully processed police report automation for carId: {}",
                                        notificationResponse.getCarId());
                            } catch (Exception e) {
                                log.error("[POLICE REPORT] Failed to process police report automation for carId: {}",
                                        notificationResponse.getCarId(), e);
                            }
                        }
                    } catch (Exception e) {
                        log.error("[POLICE REPORT] Failed to create car notification for email: {}", emailSubject, e);
                    }
                }

                // Find matching rule
                ForwardingRule matchedRule = null;
                boolean wasAiClassified = false;
                ClassificationResult aiResult = null;
                String effectiveRecipient = null;

                // First: keyword matching
                matchedRule = findKeywordMatch(emailData, rules, bodyText);

                // If no keyword match, try AI classification
                if (matchedRule == null) {
                    aiResult = classificationService.classify(emailData, rules);
                    if (aiResult.matchedRuleId() != null && aiResult.confidence() >= 0.7) {
                        final String ruleId = aiResult.matchedRuleId();
                        matchedRule = rules.stream()
                                .filter(r -> r.getId().toString().equals(ruleId))
                                .findFirst().orElse(null);
                        wasAiClassified = matchedRule != null;
                        aiClassified++;
                    }
                }

                if (matchedRule == null) {
                    // No match - log as no_match
                    saveEmailLog(userId, emailFrom, emailSubject, null, null, "no_match",
                            outlookMessageId, conversationId, false, 0.0,
                            aiResult != null ? aiResult.reasoning() : "No rule matched", null, receivedDateTime);
                    log.info("[NO MATCH] Email: {}", emailSubject);
                    continue;
                }

                // Determine recipient
                if (wasAiClassified && aiResult != null && aiResult.overrideRecipientEmail() != null) {
                    effectiveRecipient = aiResult.overrideRecipientEmail();
                    log.info("[OVERRIDE] AI recipient override: {}", effectiveRecipient);
                } else if (matchedRule.isSmartThreadEnabled() && conversationId != null) {
                    // Smart thread: check if a rule recipient already participated in this thread
                    String threadRecipient = findThreadParticipant(conversationId, accessToken, matchedRule.getId());
                    if (threadRecipient != null) {
                        effectiveRecipient = threadRecipient;
                        log.info("[SMART-THREAD] Routing to thread participant: {}", effectiveRecipient);
                    }
                }

                if (effectiveRecipient == null) {
                    if (matchedRule.isRotationEnabled()) {
                        RotationResult rotation = getNextRotationRecipient(matchedRule);
                        if (rotation == null) {
                            log.warn("[ROTATION] All recipients on vacation for rule: {}", matchedRule.getName());
                            saveEmailLog(userId, emailFrom, emailSubject, null, matchedRule.getId(), "skipped",
                                    outlookMessageId, conversationId, wasAiClassified,
                                    aiResult != null ? aiResult.confidence() : 0,
                                    "All recipients on vacation", null, receivedDateTime);
                            continue;
                        }
                        effectiveRecipient = rotation.email();
                    } else {
                        effectiveRecipient = matchedRule.getRecipientEmail();
                    }
                }

                // Build tracking token and forward
                UUID trackingToken = UUID.randomUUID();
                String trackingLink = appUrl + "/mark-replied?token=" + trackingToken;
                String replyNote = buildReplyTrackingNote(integratedEmail, trackingLink);

                try {
                    outlookService.forwardMessage(accessToken, outlookMessageId, effectiveRecipient, replyNote);

                    // Mark as read
                    markEmailRead(accessToken, outlookMessageId);

                    // Log success
                    saveEmailLog(userId, emailFrom, emailSubject, effectiveRecipient, matchedRule.getId(), "forwarded",
                            outlookMessageId, conversationId, wasAiClassified,
                            aiResult != null ? aiResult.confidence() : null,
                            aiResult != null ? aiResult.reasoning() : null,
                            trackingToken, receivedDateTime);

                    forwarded++;
                    log.info("[FORWARDED] \"{}\" → {} via rule \"{}\"", emailSubject, effectiveRecipient,
                            matchedRule.getName());

                    // Notify frontend via WebSocket
                    notifyUser(userId, "email_forwarded", Map.of(
                            "subject", emailSubject,
                            "forwardedTo", effectiveRecipient,
                            "ruleName", matchedRule.getName()));

                } catch (Exception e) {
                    log.error("[FAILED] Could not forward email: {}", emailSubject, e);
                    saveEmailLog(userId, emailFrom, emailSubject, effectiveRecipient, matchedRule.getId(), "failed",
                            outlookMessageId, conversationId, wasAiClassified,
                            aiResult != null ? aiResult.confidence() : null,
                            "Forward failed: " + e.getMessage(), null, receivedDateTime);
                }

            } catch (Exception e) {
                log.error("Error processing individual email", e);
            }
        }

        return Map.of("processed", processed, "forwarded", forwarded, "ai_classified", aiClassified);
    }

    private ForwardingRule findKeywordMatch(EmailData emailData, List<ForwardingRule> rules, String bodyText) {
        String effectiveBody = extractEffectiveBody(bodyText);
        String combinedContent = (emailData.subject() + " " + effectiveBody).toLowerCase();
        String primaryContentRaw = extractPrimaryMessage(effectiveBody).toLowerCase();
        final String primaryContent = primaryContentRaw.isEmpty() ? combinedContent : primaryContentRaw;

        for (ForwardingRule rule : rules) {
            if (!rule.isActive())
                continue;

            // Sender pattern check
            if (rule.getSenderPattern() != null && !rule.getSenderPattern().isBlank()) {
                if (!emailData.sender().toLowerCase().contains(rule.getSenderPattern().toLowerCase()))
                    continue;
            }

            // Subject pattern check
            if (rule.getSubjectPattern() != null && !rule.getSubjectPattern().isBlank()) {
                boolean subjectMatch = Arrays.stream(rule.getSubjectPattern().split(","))
                        .map(String::trim)
                        .anyMatch(kw -> !kw.isEmpty() && emailData.subject().toLowerCase().contains(kw.toLowerCase()));
                if (!subjectMatch)
                    continue;
            }

            // Keyword check
            if (rule.getKeywords() == null || rule.getKeywords().length == 0)
                continue;
            boolean hasKeywordMatch = Arrays.stream(rule.getKeywords())
                    .anyMatch(kw -> kw != null && !kw.isBlank() && primaryContent.contains(kw.toLowerCase().trim()));
            if (!hasKeywordMatch)
                continue;

            // Negative keyword check
            if (rule.getNegativeKeywords() != null && rule.getNegativeKeywords().length > 0) {
                boolean excluded = Arrays.stream(rule.getNegativeKeywords())
                        .anyMatch(
                                nk -> nk != null && !nk.isBlank() && combinedContent.contains(nk.toLowerCase().trim()));
                if (excluded)
                    continue;
            }

            return rule;
        }
        return null;
    }

    private String findThreadParticipant(String conversationId, String accessToken, UUID ruleId) {
        try {
            JsonNode threadData = outlookService.callGraphApi(accessToken,
                    "messages?$filter=conversationId eq '" + conversationId
                            + "'&$select=from,toRecipients,ccRecipients&$top=25");

            JsonNode messages = threadData.path("value");
            if (messages.size() <= 1)
                return null;

            Set<String> participantEmails = new HashSet<>();
            for (JsonNode msg : messages) {
                String fromEmail = msg.path("from").path("emailAddress").path("address").asText("").toLowerCase();
                if (!fromEmail.isEmpty())
                    participantEmails.add(fromEmail);
            }

            List<RuleRecipient> recipients = recipientRepository.findByRuleIdOrderBySortOrderAsc(ruleId);
            for (RuleRecipient r : recipients) {
                if (isOnVacation(r))
                    continue;
                if (participantEmails.contains(r.getEmail().toLowerCase())) {
                    log.info("[SMART-THREAD] Found participant: {}", r.getEmail());
                    return r.getEmail();
                }
            }
        } catch (Exception e) {
            log.error("[SMART-THREAD] Error checking thread participants", e);
        }
        return null;
    }

    private record RotationResult(String email, String displayName) {
    }

    private record PoliceReportAttachment(PoliceReportDto reportDto, byte[] fileBytes, String fileName) {
    }

    private RotationResult getNextRotationRecipient(ForwardingRule rule) {
        List<RuleRecipient> recipients = recipientRepository.findByRuleIdOrderBySortOrderAsc(rule.getId());
        if (recipients.isEmpty())
            return new RotationResult(rule.getRecipientEmail(), null);

        List<RuleRecipient> available = recipients.stream()
                .filter(r -> !isOnVacation(r)).toList();
        if (available.isEmpty())
            return null;

        // Re-fetch current index to avoid race conditions
        ForwardingRule freshRule = ruleRepository.findById(rule.getId()).orElse(rule);
        int currentIndex = freshRule.getCurrentRotationIndex();
        int nextIndex = currentIndex % available.size();
        RuleRecipient selected = available.get(nextIndex);

        ruleRepository.updateRotationIndex(rule.getId(), currentIndex + 1);

        log.info("[ROTATION] Selected {} (index {})", selected.getEmail(), nextIndex);
        return new RotationResult(selected.getEmail(), selected.getDisplayName());
    }

    private boolean isOnVacation(RuleRecipient r) {
        if (r.isOnVacation())
            return true;
        if (r.getVacationStart() != null && r.getVacationEnd() != null) {
            OffsetDateTime now = OffsetDateTime.now();
            return !now.isBefore(r.getVacationStart()) && !now.isAfter(r.getVacationEnd());
        }
        return false;
    }

    private void markEmailRead(String accessToken, String messageId) {
        outlookService.patchMessage(accessToken, messageId, "{\"isRead\": true}");
    }

    private void updateReplyViaCC(UUID userId, JsonNode email, String integratedEmail) {
        String conversationId = email.path("conversationId").asText(null);
        if (conversationId == null)
            return;

        JsonNode toRecipients = email.path("toRecipients");
        String receivedAt = email.path("receivedDateTime").asText(null);

        for (JsonNode toRec : toRecipients) {
            String toEmail = toRec.path("emailAddress").path("address").asText("").toLowerCase();
            if (!toEmail.isEmpty()) {
                emailLogRepository
                        .findByUserIdAndStatusAndReplyDetectedFalseAndOutlookConversationIdIsNotNull(userId,
                                "forwarded")
                        .stream()
                        .filter(l -> l.getEmailFrom() != null && l.getEmailFrom().toLowerCase().equals(toEmail))
                        .findFirst()
                        .ifPresent(l -> {
                            l.setReplyDetected(true);
                            l.setRepliedAt(
                                    receivedAt != null ? OffsetDateTime.parse(receivedAt) : OffsetDateTime.now());
                            l.setReplySource("cc_detection");
                            emailLogRepository.save(l);
                            log.info("[CC REPLY] Marked reply for log {}", l.getId());
                        });
            }
        }
    }

    @Transactional
    public void manualAssign(UUID userId, String outlookMessageId, UUID ruleId) {
        String accessToken = outlookService.getValidAccessToken(userId);

        ForwardingRule rule = ruleRepository.findByIdAndUserId(ruleId, userId)
                .orElseThrow(() -> new RuntimeException("Rule not found"));

        OutlookConnection connection = connectionRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("No Outlook connection"));

        // Fetch email details
        JsonNode email;
        try {
            email = outlookService.callGraphApi(accessToken,
                    "messages/" + outlookMessageId
                            + "?$select=id,subject,from,body,bodyPreview,conversationId,hasAttachments,receivedDateTime");
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch email: " + e.getMessage());
        }

        String emailSubject = email.path("subject").asText("");
        String emailFrom = email.path("from").path("emailAddress").path("address").asText("");
        String conversationId = email.path("conversationId").asText(null);
        String receivedDateTime = email.path("receivedDateTime").asText(null);

        UUID trackingToken = UUID.randomUUID();
        String trackingLink = appUrl + "/mark-replied?token=" + trackingToken;
        String integratedEmail = connection.getEmailAddress() != null ? connection.getEmailAddress() : "";
        String replyNote = buildReplyTrackingNote(integratedEmail, trackingLink);

        // Remove any existing no_match/failed log for this message
        emailLogRepository.deleteByOutlookMessageIdAndUserIdAndStatusIn(
                outlookMessageId, userId, List.of("no_match", "failed"));

        // Forward
        outlookService.forwardMessage(accessToken, outlookMessageId, rule.getRecipientEmail(), replyNote);

        // Mark as read
        markEmailRead(accessToken, outlookMessageId);

        // Log
        saveEmailLog(userId, emailFrom, emailSubject, rule.getRecipientEmail(), rule.getId(), "forwarded",
                outlookMessageId, conversationId, false, null,
                "Manually assigned by user", trackingToken, receivedDateTime);

        log.info("[MANUAL ASSIGN] \"{}\" → {} via rule \"{}\"", emailSubject, rule.getRecipientEmail(), rule.getName());

        notifyUser(userId, "email_forwarded", Map.of("subject", emailSubject, "forwardedTo", rule.getRecipientEmail()));
    }

    @Transactional
    public Map<String, Object> retryFailedEmails(UUID userId) {
        String accessToken = outlookService.getValidAccessToken(userId);
        List<ForwardingRule> rules = ruleRepository.findByUserIdAndActiveTrueOrderByPriorityAscCreatedAtDesc(userId);

        List<EmailLog> failedLogs = emailLogRepository.findByUserIdAndStatus(userId, "failed");

        int retried = 0, succeeded = 0;
        for (EmailLog failedLog : failedLogs) {
            if (failedLog.getOutlookMessageId() == null)
                continue;
            retried++;
            try {
                outlookService.forwardMessage(accessToken, failedLog.getOutlookMessageId(),
                        failedLog.getForwardedTo(), "");
                failedLog.setStatus("forwarded");
                emailLogRepository.save(failedLog);
                succeeded++;
            } catch (Exception e) {
                log.error("Retry failed for log {}", failedLog.getId(), e);
            }
        }

        return Map.of("retried", retried, "succeeded", succeeded);
    }

    private void saveEmailLog(UUID userId, String emailFrom, String emailSubject, String forwardedTo,
            UUID ruleMatched, String status, String outlookMessageId, String conversationId,
            boolean aiClassified, Double aiConfidence, String aiReasoning,
            UUID trackingToken, String receivedDateTime) {
        EmailLog log = new EmailLog();
        log.setUserId(userId);
        log.setEmailFrom(emailFrom);
        log.setEmailSubject(emailSubject);
        log.setForwardedTo(forwardedTo);
        log.setRuleMatched(ruleMatched);
        log.setStatus(status);
        log.setOutlookMessageId(outlookMessageId);
        log.setOutlookConversationId(conversationId);
        log.setAiClassified(aiClassified);
        log.setAiConfidence(aiConfidence);
        log.setAiReasoning(aiReasoning);
        log.setTrackingToken(trackingToken);
        if (receivedDateTime != null && !receivedDateTime.isEmpty()) {
            try {
                log.setReceivedAt(OffsetDateTime.parse(receivedDateTime));
            } catch (Exception e) {
                // ignore parse error
            }
        }
        emailLogRepository.save(log);
    }

    private void notifyUser(UUID userId, String event, Map<String, Object> data) {
        try {
            messagingTemplate.convertAndSend("/topic/user/" + userId, Map.of("event", event, "data", data));
        } catch (Exception e) {
            // WebSocket notification failure is non-critical
        }
    }

    private String buildReplyTrackingNote(String integratedEmail, String trackingLinkUrl) {
        return "<div style=\"font-family: Arial, sans-serif; margin-bottom: 25px;\">" +
                "<div style=\"border-left: 4px solid #0C799A; padding: 16px 20px; margin-bottom: 20px; background: #f0f9ff; border-radius: 0 8px 8px 0;\">"
                +
                "<p style=\"color: #0C799A; font-size: 15px; font-weight: 600; margin: 0 0 8px 0;\">📧 AI Email Agent - Forwarded Email</p>"
                +
                "<p style=\"color: #475569; font-size: 13px;\">This email was automatically forwarded by <strong>AI Email Agent</strong>.</p>"
                +
                "</div>" +
                "<div style=\"border: 2px solid #C1272D; border-radius: 10px; padding: 20px; background: #ffffff;\">" +
                "<p style=\"color: #C1272D; font-weight: 700; font-size: 15px; margin: 0 0 12px 0;\">📬 Reply Tracking</p>"
                +
                "<p style=\"color: #64748b; font-size: 13px; margin: 0 0 14px 0;\">To record your reply:</p>" +
                "<p style=\"color: #334155; font-size: 13px;\">1. Reply directly from <strong>" + integratedEmail
                + "</strong></p>" +
                "<p style=\"color: #334155; font-size: 13px;\">2. CC <strong>" + integratedEmail
                + "</strong> in your reply</p>" +
                "<p style=\"color: #334155; font-size: 13px;\">3. <a href=\"" + trackingLinkUrl
                + "\" style=\"color: #C1272D; text-decoration: underline;\">Click here to confirm reply</a></p>" +
                "</div></div>";
    }

    // ---- Text processing utilities ----

    public static String extractTextFromHtml(String html) {
        if (html == null || html.isEmpty())
            return "";
        String text = html.replaceAll("<script[^>]*>[\\s\\S]*?</script>", "");
        text = text.replaceAll("<style[^>]*>[\\s\\S]*?</style>", "");
        text = text.replaceAll("</?(?:div|p|br|hr|h[1-6]|li|tr|table)[^>]*>", "\n");
        text = text.replaceAll("<[^>]*>", " ");
        text = text.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'");
        text = text.replaceAll("[^\\S\n]+", " ").replaceAll("\n\\s*\n", "\n");
        return text.trim();
    }

    private String extractEffectiveBody(String bodyText) {
        String stripped = stripSignature(bodyText);
        String primary = extractPrimaryMessage(stripped);
        if (primary != null && primary.length() > 30)
            return primary;

        String[] lines = stripped.split("\n");
        int headerEnd = -1;
        boolean inHeader = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.matches("(?i)-{2,}\\s*(Original Message|Forwarded message)\\s*-{2,}") ||
                    line.matches("(?i)From:\\s+.+")) {
                inHeader = true;
            }
            if (inHeader) {
                if (line.matches("(?i)(From|Sent|To|Cc|Subject|Date|Importance):\\s*.*") ||
                        line.matches("(?i)-{2,}.*") || line.isEmpty()) {
                    headerEnd = i;
                    continue;
                }
                headerEnd = i - 1;
                break;
            }
        }
        if (headerEnd >= 0 && headerEnd < lines.length - 1) {
            String forwardedBody = String.join("\n", Arrays.copyOfRange(lines, headerEnd + 1, lines.length)).trim();
            if (!forwardedBody.isEmpty()) {
                String primary2 = extractPrimaryMessage(forwardedBody);
                return (primary2 != null && primary2.length() > 10) ? primary2 : forwardedBody;
            }
        }
        return stripped;
    }

    private String stripSignature(String text) {
        if (text == null || text.isEmpty())
            return "";
        String[] lines = text.split("\n");
        int cutIndex = lines.length;
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.equals("--") || line.equals("---") || line.matches("[-_=]{3,}") ||
                    line.matches("(?i)regards,?") || line.matches("(?i)best regards,?") ||
                    line.matches("(?i)kind regards,?") || line.matches("(?i)thanks,?") ||
                    line.matches("(?i)thank you,?") || line.matches("(?i)sincerely,?") ||
                    line.matches("(?i)cheers,?") || line.matches("(?i)warm regards,?")) {
                cutIndex = i;
                break;
            }
        }
        return String.join("\n", Arrays.copyOfRange(lines, 0, cutIndex)).trim();
    }

    private String extractPrimaryMessage(String text) {
        if (text == null || text.isEmpty())
            return "";
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.matches("(?i)-{2,}\\s*Original Message\\s*-{2,}") ||
                    line.matches("(?i)-{2,}\\s*Forwarded message\\s*-{2,}") ||
                    line.matches("(?i)From:\\s+.+") ||
                    line.matches("(?i)On\\s+.+wrote:\\s*") ||
                    line.matches("_{5,}") ||
                    line.startsWith(">")) {
                return String.join("\n", Arrays.copyOfRange(lines, 0, i)).trim();
            }
        }
        return text;
    }

    private record ForwardedInfo(boolean isForwarded, String originalSender, String originalSubject,
            String originalDate) {
    }

    private ForwardedInfo parseForwardedHeaders(String body) {
        if (body == null || body.isEmpty())
            return new ForwardedInfo(false, null, null, null);

        String bodyLower = body.toLowerCase();
        boolean isForwarded = bodyLower.contains("forwarded message") || bodyLower.contains("original message")
                || bodyLower.contains("fw:") || bodyLower.contains("fwd:");

        if (!isForwarded)
            return new ForwardedInfo(false, null, null, null);

        String originalSender = null, originalSubject = null, originalDate = null;

        Matcher fromMatcher = Pattern.compile("(?i)From:\\s*([^\\n<]+?)(?:\\s*<([^>]+)>)?[\\s\\n]")
                .matcher(body);
        if (fromMatcher.find()) {
            originalSender = fromMatcher.group(2) != null ? fromMatcher.group(2).trim() : fromMatcher.group(1).trim();
        }

        Matcher subjectMatcher = Pattern.compile("(?i)Subject:\\s*([^\\n]+)").matcher(body);
        if (subjectMatcher.find())
            originalSubject = subjectMatcher.group(1).trim();

        Matcher dateMatcher = Pattern.compile("(?i)(Date|Sent):\\s*([^\\n]+)").matcher(body);
        if (dateMatcher.find())
            originalDate = dateMatcher.group(2).trim();

        return new ForwardedInfo(true, originalSender, originalSubject, originalDate);
    }

    private String getOcrBearerToken() throws Exception {
        // Cache token for 25 minutes (server timeout is 30)
        if (cachedOcrToken != null && System.currentTimeMillis() - cachedOcrTokenFetchedAt < 25 * 60 * 1000L) {
            return cachedOcrToken;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> loginBody = Map.of("username", ocrAuthUsername, "password", ocrAuthPassword);
        HttpEntity<Map<String, String>> request = new HttpEntity<>(loginBody, headers);
        ResponseEntity<String> response = ocrRestTemplate.postForEntity(ocrAuthUrl + "/v1/api/auth/login", request,
                String.class);
        JsonNode root = objectMapper.readValue(response.getBody(), JsonNode.class);
        cachedOcrToken = root.path("data").path("token").asText();
        cachedOcrTokenFetchedAt = System.currentTimeMillis();
        log.debug("[OCR] Fetched new bearer token");
        return cachedOcrToken;
    }

    private PoliceReportAttachment hasPoliceReportAttachment(String accessToken, String messageId) {
        try {
            String bearerToken = getOcrBearerToken();

            JsonNode attachmentsData = outlookService.callGraphApi(accessToken,
                    "messages/" + messageId + "/attachments?$select=name,contentType,contentBytes");
            JsonNode attachments = attachmentsData.path("value");

            for (JsonNode attachment : attachments) {
                String contentBytesBase64 = attachment.path("contentBytes").asText("");
                if (contentBytesBase64.isEmpty())
                    continue;

                byte[] fileBytes = java.util.Base64.getDecoder().decode(contentBytesBase64);
                String fileName = attachment.path("name").asText("attachment");
                String contentType = attachment.path("contentType").asText("application/octet-stream");

                MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
                body.add("file", new ByteArrayResource(fileBytes) {
                    @Override
                    public String getFilename() {
                        return fileName;
                    }
                });
                body.add("docType", "police-report");

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.MULTIPART_FORM_DATA);
                headers.setBearerAuth(bearerToken);
                HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

                ResponseEntity<String> response = ocrRestTemplate.postForEntity(
                        ocrBaseUrl + "/v1/api/ai-gateway/ocr/upload", requestEntity, String.class);
                String responseBody = response.getBody();

                if (responseBody == null)
                    continue;

                PoliceReportDto ocrResult = objectMapper.readValue(responseBody, PoliceReportDto.class);
                if ("police-report".equals(ocrResult.getMeta().getDocumentType())) {
                    log.info("[OCR] Police report found in attachment: {}", fileName);
                    return new PoliceReportAttachment(ocrResult, fileBytes, fileName);
                }
            }
        } catch (Exception e) {
            log.warn("[OCR] Failed to classify attachments for message {}: {}", messageId, e.getMessage());
        }
        return null;
    }

    private boolean isInRecipients(JsonNode recipients, String email) {
        if (email == null || email.isEmpty())
            return false;
        for (JsonNode r : recipients) {
            if (email.equals(r.path("emailAddress").path("address").asText("").toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private NewNotificationByReportResponse createCarNotificationByPoliceReport(PoliceReportDto policeReport)
            throws Exception {
        String bearerToken = getOcrBearerToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(bearerToken);

        HttpEntity<PoliceReportDto> request = new HttpEntity<>(policeReport, headers);

        ResponseEntity<String> response = ocrRestTemplate.postForEntity(
                callCenterBaseUrl + "/v1/api/call-center/cars-notification/create-by-police-report",
                request,
                String.class);

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            JsonNode apiResponse = objectMapper.readValue(response.getBody(), JsonNode.class);
            JsonNode data = apiResponse.path("data");

            if (!data.isMissingNode()) {
                NewNotificationByReportResponse notificationResponse = objectMapper.treeToValue(data,
                        NewNotificationByReportResponse.class);

                log.info("[POLICE REPORT] Car notification created - Notification ID: {}, Visa: {}, Car ID: {}, Report Number: {}",
                        notificationResponse.getNotificationId(),
                        notificationResponse.getNotificationVisa(),
                        notificationResponse.getCarId(),
                        notificationResponse.getReportNumber());

                return notificationResponse;
            }
        }

        log.warn("[POLICE REPORT] Unexpected response status: {}", response.getStatusCode());
        throw new RuntimeException("Failed to create car notification: " + response.getStatusCode());
    }

    private void processPoliceReportAutomation(String carId, byte[] fileBytes, String fileName) throws Exception {
        String bearerToken = getOcrBearerToken();

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("policeReport", new ByteArrayResource(fileBytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(bearerToken);

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        String url = dataManagementBaseUrl + "/v1/api/data-management/data-reception/police-report-automation?carId="
                + carId;

        ResponseEntity<String> response = ocrRestTemplate.postForEntity(url, requestEntity, String.class);

        if (response.getStatusCode().is2xxSuccessful()) {
            log.info("[POLICE REPORT AUTOMATION] Successfully processed for carId: {}", carId);
        } else {
            log.warn("[POLICE REPORT AUTOMATION] Unexpected response status: {}", response.getStatusCode());
            throw new RuntimeException("Failed to process police report automation: " + response.getStatusCode());
        }
    }
}
