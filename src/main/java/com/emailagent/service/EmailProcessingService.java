package com.emailagent.service;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DataIntegrityViolationException;
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
import com.emailagent.model.User;
import com.emailagent.repository.EmailLogRepository;
import com.emailagent.repository.ForwardingRuleRepository;
import com.emailagent.repository.OutlookConnectionRepository;
import com.emailagent.repository.RuleRecipientRepository;
import com.emailagent.repository.UserRepository;
import com.emailagent.repository.UserRoleRepository;
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
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final OutlookService outlookService;
    private final EmailClassificationService classificationService;
    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;
    @Qualifier("emailProcessingExecutor")
    private final Executor emailProcessingExecutor;

    @Value("${app.url}")
    private String appUrl;

    @Value("${app.processing.enabled}")
    private boolean processingEnabled;

    @Value("${app.ocr.url}")
    private String ocrBaseUrl;

    @Value("${app.ocr.auth.url}")
    private String ocrAuthUrl;

    @Value("${app.ocr.auth.username}")
    private String ocrAuthUsername;

    @Value("${app.ocr.auth.password}")
    private String ocrAuthPassword;

    @Value("${app.callcenter.url}")
    private String callCenterBaseUrl;

    @Value("${app.datamanagement.url}")
    private String dataManagementBaseUrl;

    @Value("${ai.confidence-threshold}")
    private double aiConfidenceThreshold;

    private final ConcurrentHashMap<UUID, ReentrantLock> userProcessingLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> userLastProcessedAt = new ConcurrentHashMap<>();
    private static final long MIN_REPROCESS_INTERVAL_MS = 60_000; // 1 minute
    private final RestTemplate ocrRestTemplate = new RestTemplate();
    private String cachedOcrToken;
    private long cachedOcrTokenFetchedAt = 0;

    // Header lines that appear in forwarded/replied chains
    private static final Pattern HEADER_LINE = Pattern.compile(
            "^\\s*(from|to|cc|bcc|sent|date|subject|reply-to)\\s*:.*$",
            Pattern.CASE_INSENSITIVE);

    // "On <date>, <person> wrote:" reply attribution lines
    private static final Pattern REPLY_ATTR = Pattern.compile(
            "^\\s*on\\s+.+\\b(wrote|sent)\\s*:?\\s*$",
            Pattern.CASE_INSENSITIVE);

    // Forward / begin markers
    private static final Pattern FORWARD_MARKER = Pattern.compile(
            "^\\s*(-+\\s*forwarded message\\s*-+|begin forwarded message:?|"
                    + ".*forwarded (this )?email.*|.*ai email agent.*)\\s*$",
            Pattern.CASE_INSENSITIVE);

    // Signature start: "regards", "best regards", "kind regards", "thanks & best
    // regards", etc.
    private static final Pattern SIGNATURE_START = Pattern.compile(
            "^\\s*(regards|best regards|kind regards|warm regards|"
                    + "yours sincerely|sincerely|thanks (&|and) best regards|"
                    + "thank you for your understanding|best)\\s*[.,]?\\s*$",
            Pattern.CASE_INSENSITIVE);

    // Sign-off / mobile footers
    private static final Pattern FOOTER_LINE = Pattern.compile(
            "^\\s*(sent from .*|.*central bank registration.*|"
                    + ".*get in touch with us.*|p\\.?o\\.?\\s*box.*|"
                    + "t\\s*:?-?\\s*\\+?\\d.*|d\\s*:?-?\\s*\\+?\\d.*|"
                    + "e\\s*:?-?\\s*\\S+@\\S+.*|w\\s*:?-?\\s*https?://.*|"
                    + "mob\\s*:.*|tel\\s*:.*|web\\s*:.*|"
                    + "google link\\s*:.*|https?://\\S+\\s*$)",
            Pattern.CASE_INSENSITIVE);

    // Reply-tracking instruction block (your AI agent boilerplate)
    private static final Pattern TRACKING_LINE = Pattern.compile(
            "^\\s*(reply tracking|to record your reply|"
                    + "\\d+\\.\\s*(reply directly|cc |click here).*|click here to confirm.*)\\s*$",
            Pattern.CASE_INSENSITIVE);

    // Confidentiality disclaimers appended after signatures
    private static final Pattern DISCLAIMER_LINE = Pattern.compile(
            "^\\s*(?:this\\s+(?:e-?mail|message|email|communication)\\b.*\\b(?:confidential|intended|privileged)|"
                    + "please\\s+do\\s+not\\s+(?:print|forward|share|distribute)\\s+this|"
                    + "if\\s+you\\s+(?:are\\s+not|have\\s+received\\s+this)\\b.*\\b(?:intended|addressee|recipient)|"
                    + ".*\\bintended\\s+solely\\s+for\\b|"
                    + ".*\\bmay\\s+contain\\b.*\\b(?:privileged|confidential|proprietary)\\s+information|"
                    + ".*\\bunauthori[sz]ed\\s+(?:use|access|disclosure|distribution)|"
                    + "confidentiality\\s+notice|legal\\s+(?:notice|disclaimer)).*$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Scheduled job: process unread emails for all users every 5 minutes.
     */
    @Scheduled(cron = "${app.processing.cron}")
    public void scheduledProcessEmails() {
        if (!processingEnabled)
            return;
        log.info("Starting scheduled email processing...");
        processAllUsers();
    }

    public Map<String, Object> processAllUsers() {
        List<OutlookConnection> connections = connectionRepository.findAll();
        if (connections.isEmpty()) {
            log.info("No Outlook connections found");
            return Map.of("message", "No connections to process");
        }

        for (OutlookConnection connection : connections) {
            CompletableFuture.runAsync(() -> {
                try {
                    processUserEmails(connection);
                } catch (Exception e) {
                    log.error("Error processing emails for user {}", connection.getUserId(), e);
                }
            }, emailProcessingExecutor);
        }

        log.info("Email processing started asynchronously for {} user(s)", connections.size());
        return Map.of("message", "Processing started", "users", connections.size());
    }

    /**
     * Process emails for a specific user (can also be called on-demand from the
     * API).
     */
    public Map<String, Object> processForUser(UUID userId) {
        OutlookConnection connection = connectionRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("No Outlook connection"));
        CompletableFuture.runAsync(() -> processUserEmails(connection), emailProcessingExecutor);
        return Map.of("message", "Processing started");
    }

    private Map<String, Integer> processUserEmails(OutlookConnection connection) {
        UUID userId = connection.getUserId();

        long now = System.currentTimeMillis();
        Long lastRun = userLastProcessedAt.get(userId);
        if (lastRun != null && (now - lastRun) < MIN_REPROCESS_INTERVAL_MS) {
            log.info("Skipping email processing for user {} — last run was {}ms ago", userId, now - lastRun);
            return Map.of("processed", 0, "forwarded", 0, "ai_classified", 0);
        }

        ReentrantLock lock = userProcessingLocks.computeIfAbsent(userId, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            log.info("Skipping email processing for user {} — already in progress", userId);
            return Map.of("processed", 0, "forwarded", 0, "ai_classified", 0);
        }

        try {
            userLastProcessedAt.put(userId, now);
            return doProcessUserEmails(connection);
        } finally {
            lock.unlock();
        }
    }

    private Map<String, Integer> doProcessUserEmails(OutlookConnection connection) {
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
        Set<String> processedInThisRun = new HashSet<>();

        for (JsonNode email : emails) {
            try {
                String outlookMessageId = email.get("id").asText();
                String emailSubject = email.path("subject").asText("");
                String emailFrom = email.path("from").path("emailAddress").path("address").asText("");

                // Skip if seen earlier in this batch (in-memory, avoids DB call)
                if (processedInThisRun.contains(outlookMessageId)) {
                    log.debug("[SKIP] Duplicate in batch: {}", emailSubject);
                    continue;
                }
                // Skip only if already successfully forwarded — failed/no_match emails must be
                // retried
                if (emailLogRepository.existsByOutlookMessageIdAndUserIdAndStatus(outlookMessageId, userId,
                        "forwarded")) {
                    processedInThisRun.add(outlookMessageId);
                    log.debug("[SKIP] Already forwarded: {}", emailSubject);
                    continue;
                }
                processedInThisRun.add(outlookMessageId);

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

                ThreadSplit threadSplit = splitThreadBody(bodyText);
                String lastMessage = threadSplit.lastMessage(); // latest reply only
                String previousMessages = threadSplit.previousMessages(); // all prior thread content

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
                List<BigDecimal> policeReportVisas = new ArrayList<>();
                if (hasAttachments) {
                    policeReportVisas = processAttachments(accessToken, outlookMessageId, emailSubject);
                }

                // Find matching rule
                ForwardingRule matchedRule = null;
                boolean wasAiClassified = false;
                ClassificationResult aiResult = null;
                String effectiveRecipient = null;
                String matchedKeyword = null;

                // First: keyword matching
                KeywordMatchResult keywordResult = findSenderAndSubjectMatch(emailData, rules, bodyText);
                if (keywordResult != null) {
                    matchedRule = keywordResult.rule();
                    matchedKeyword = keywordResult.matchedKeyword();
                }

                // If no keyword match, try AI classification
                if (matchedRule == null) {
                    aiResult = classificationService.classify(lastMessage, previousMessages, rules);
                    if (aiResult.matchedRuleId() != null && aiResult.confidence() >= aiConfidenceThreshold) {
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
                            aiResult != null ? aiResult.reasoning() : "No rule matched", null, receivedDateTime,
                            null, null, null);
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
                                    "All recipients on vacation", null, receivedDateTime, null, null,
                                    matchedKeyword);
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
                String replyNote = buildReplyTrackingNote(integratedEmail, trackingLink, policeReportVisas);

                try {
                    try {
                        outlookService.forwardMessage(accessToken, outlookMessageId, effectiveRecipient, replyNote);
                    } catch (Exception forwardEx) {
                        if (isAuthError(forwardEx)) {
                            log.warn("[TOKEN] Token expired mid-process, refreshing and retrying for: {}",
                                    emailSubject);
                            accessToken = outlookService.refreshAccessToken(connection);
                            outlookService.forwardMessage(accessToken, outlookMessageId, effectiveRecipient, replyNote);
                        } else {
                            throw forwardEx;
                        }
                    }

                    // Mark as read
                    markEmailRead(accessToken, outlookMessageId);

                    // Log success
                    saveEmailLog(userId, emailFrom, emailSubject, effectiveRecipient, matchedRule.getId(), "forwarded",
                            outlookMessageId, conversationId, wasAiClassified,
                            aiResult != null ? aiResult.confidence() : null,
                            aiResult != null ? aiResult.reasoning() : null,
                            trackingToken, receivedDateTime, null, null, matchedKeyword);

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
                            "Forward failed: " + e.getMessage(), null, receivedDateTime, null, null,
                            matchedKeyword);
                }

            } catch (Exception e) {
                log.error("Error processing individual email", e);
            }
        }

        return Map.of("processed", processed, "forwarded", forwarded, "ai_classified", aiClassified);

    }

    private KeywordMatchResult findSenderAndSubjectMatch(EmailData emailData, List<ForwardingRule> rules,
            String bodyText) {

        for (ForwardingRule rule : rules) {
            if (!rule.isActive())
                continue;

            // Sender pattern check
            if (rule.getSenderPattern() != null && !rule.getSenderPattern().isBlank()) {
                if (emailData.sender().toLowerCase().contains(rule.getSenderPattern().toLowerCase()))
                    return new KeywordMatchResult(rule, "sender:" + rule.getSenderPattern());
            }

            // Subject pattern check
            if (rule.getSubjectPattern() != null && !rule.getSubjectPattern().isBlank()) {
                String matchedSubjectKw = Arrays.stream(rule.getSubjectPattern().split(","))
                        .map(String::trim)
                        .filter(kw -> !kw.isEmpty() && emailData.subject().toLowerCase().contains(kw.toLowerCase()))
                        .findFirst().orElse(null);
                if (matchedSubjectKw != null)
                    return new KeywordMatchResult(rule, "subject:" + matchedSubjectKw);
            }
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
        UUID effectiveUserId = resolveEffectiveUserId(userId);
        String accessToken = outlookService.getValidAccessToken(userId);

        ForwardingRule rule = ruleRepository.findByIdAndUserId(ruleId, effectiveUserId)
                .orElseThrow(() -> new RuntimeException("Rule not found"));

        OutlookConnection connection = connectionRepository.findByUserId(effectiveUserId)
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
        String replyNote = buildReplyTrackingNote(integratedEmail, trackingLink, null);

        // Remove any existing no_match/failed log for this message
        emailLogRepository.deleteByOutlookMessageIdAndUserIdAndStatusIn(
                outlookMessageId, effectiveUserId, List.of("no_match", "failed"));

        // Forward
        outlookService.forwardMessage(accessToken, outlookMessageId, rule.getRecipientEmail(), replyNote);

        // Mark as read
        markEmailRead(accessToken, outlookMessageId);

        // Log
        saveEmailLog(effectiveUserId, emailFrom, emailSubject, rule.getRecipientEmail(), rule.getId(), "forwarded",
                outlookMessageId, conversationId, false, null,
                "Manually assigned by user", trackingToken, receivedDateTime, null, null, null);

        log.info("[MANUAL ASSIGN] \"{}\" → {} via rule \"{}\"", emailSubject, rule.getRecipientEmail(), rule.getName());

        notifyUser(effectiveUserId, "email_forwarded",
                Map.of("subject", emailSubject, "forwardedTo", rule.getRecipientEmail()));
    }

    @Transactional
    public Map<String, Object> retryFailedEmails(UUID userId) {
        UUID effectiveUserId = resolveEffectiveUserId(userId);
        String accessToken = outlookService.getValidAccessToken(userId);

        List<EmailLog> failedLogs = emailLogRepository.findByUserIdAndStatus(effectiveUserId, "failed");

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

    private UUID resolveEffectiveUserId(UUID userId) {
        String role = userRoleRepository.findByUserId(userId)
                .map(ur -> ur.getRole())
                .orElse("user");
        if (!"user".equals(role)) {
            return userId;
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getDepartmentId() == null) {
            throw new RuntimeException("User is not assigned to a department");
        }
        return userRepository.findAdminByDepartmentId(user.getDepartmentId())
                .map(User::getId)
                .orElseThrow(() -> new RuntimeException("No admin found for department"));
    }

    private void saveEmailLog(UUID userId, String emailFrom, String emailSubject, String forwardedTo,
            UUID ruleMatched, String status, String outlookMessageId, String conversationId,
            boolean aiClassified, Double aiConfidence, String aiReasoning,
            UUID trackingToken, String receivedDateTime, String negativeKeywordOverride,
            String negativeKeywordOverrideLog, String matchedKeyword) {
        EmailLog emailLog = new EmailLog();
        emailLog.setUserId(userId);
        emailLog.setEmailFrom(emailFrom);
        emailLog.setEmailSubject(emailSubject);
        emailLog.setForwardedTo(forwardedTo);
        emailLog.setRuleMatched(ruleMatched);
        emailLog.setStatus(status);
        emailLog.setOutlookMessageId(outlookMessageId);
        emailLog.setOutlookConversationId(conversationId);
        emailLog.setAiClassified(aiClassified);
        emailLog.setAiConfidence(aiConfidence);
        emailLog.setAiReasoning(aiReasoning);
        emailLog.setTrackingToken(trackingToken);
        emailLog.setNegativeKeywordOverride(negativeKeywordOverride);
        emailLog.setNegativeKeywordOverrideLog(negativeKeywordOverrideLog);
        emailLog.setMatchedKeyword(matchedKeyword);
        if (receivedDateTime != null && !receivedDateTime.isEmpty()) {
            try {
                emailLog.setReceivedAt(OffsetDateTime.parse(receivedDateTime));
            } catch (Exception e) {
                // ignore parse error
            }
        }
        if (outlookMessageId != null
                && emailLogRepository.existsByOutlookMessageIdAndUserId(outlookMessageId, userId)) {
            if ("forwarded".equals(status)) {
                // Upgrade stale failed/no_match/skipped record to forwarded
                emailLogRepository.deleteByOutlookMessageIdAndUserIdAndStatusIn(
                        outlookMessageId, userId, List.of("failed", "no_match", "skipped"));
                if (emailLogRepository.existsByOutlookMessageIdAndUserId(outlookMessageId, userId)) {
                    log.debug("[DUPLICATE] Email already forwarded for message {} / user {}, skipping insert",
                            outlookMessageId, userId);
                    return;
                }
            } else {
                log.debug("[DUPLICATE] Email log already exists for message {} / user {}, skipping insert",
                        outlookMessageId, userId);
                return;
            }
        }
        try {
            emailLogRepository.save(emailLog);
        } catch (DataIntegrityViolationException e) {
            log.warn("[DUPLICATE] Race condition on email log insert for message {} / user {}, skipping",
                    outlookMessageId, userId);
        }
    }

    private void notifyUser(UUID userId, String event, Map<String, Object> data) {
        try {
            messagingTemplate.convertAndSend("/topic/user/" + userId, Map.of("event", event, "data", data));
        } catch (Exception e) {
            // WebSocket notification failure is non-critical
        }
    }

    private String buildReplyTrackingNote(String integratedEmail, String trackingLinkUrl,
            List<BigDecimal> notificationVisas) {
        String claimBanner = "";
        if (notificationVisas != null && !notificationVisas.isEmpty()) {
            boolean multiple = notificationVisas.size() > 1;
            String visaList = notificationVisas.stream()
                    .map(v -> "<strong>" + v.toPlainString() + "</strong>")
                    .collect(Collectors.joining(", "));
            claimBanner = "<div style=\"border: 2px solid #16a34a; border-radius: 10px; padding: 16px 20px; margin-bottom: 20px; background: #f0fdf4;\">"
                    + "<p style=\"color: #15803d; font-weight: 700; font-size: 15px; margin: 0 0 6px 0;\">✅ "
                    + (multiple ? "New Claims Created" : "New Claim Created") + "</p>"
                    + "<p style=\"color: #166534; font-size: 13px; margin: 0;\">"
                    + (multiple ? notificationVisas.size() + " new claims have" : "A new claim has")
                    + " been automatically created from the attached police report. "
                    + (multiple ? "Claim Visas: " : "Claim Visa: ") + visaList + "</p>"
                    + "</div>";
        }

        return "<div style=\"font-family: Arial, sans-serif; margin-bottom: 25px;\">" +
                claimBanner +
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

    public static String extractBody(String raw) {
        // Normalize and split
        String[] lines = raw.replace("\r\n", "\n").split("\n");
        List<String> out = new ArrayList<>();

        boolean inSignature = false;

        for (String line : lines) {
            String t = line.strip();

            if (t.isEmpty()) {
                if (!out.isEmpty() && !out.get(out.size() - 1).isEmpty())
                    out.add("");
                continue;
            }

            // Once a signature starts, skip everything until the next
            // structural marker (new message header / forward)
            if (inSignature) {
                if (HEADER_LINE.matcher(t).matches()
                        || FORWARD_MARKER.matcher(t).matches()
                        || REPLY_ATTR.matcher(t).matches()) {
                    inSignature = false; // a new message segment begins
                } else {
                    continue; // still inside signature/footer
                }
            }

            if (SIGNATURE_START.matcher(t).matches()) {
                inSignature = true;
                continue;
            }
            if (HEADER_LINE.matcher(t).matches())
                continue;
            if (REPLY_ATTR.matcher(t).matches())
                continue;
            if (FORWARD_MARKER.matcher(t).matches())
                continue;
            if (FOOTER_LINE.matcher(t).matches())
                continue;
            if (TRACKING_LINE.matcher(t).matches())
                continue;

            // Strip leading quote markers ">" if present
            t = t.replaceFirst("^>+\\s*", "");

            out.add(t);
        }

        // Collapse multiple blank lines and trim
        return out.stream()
                .collect(Collectors.joining("\n"))
                .replaceAll("\n{3,}", "\n\n")
                .strip();
    }

    private ThreadSplit splitThreadBody(String bodyText) {
        if (bodyText == null || bodyText.isEmpty())
            return new ThreadSplit("", "");

        // Strip signatures and disclaimers from all messages in the thread first,
        // preserving thread-separator headers (From:/Sent:/To: etc.) so splitting still
        // works.
        String cleaned = cleanSignaturesAndDisclaimers(bodyText);
        String[] lines = cleaned.split("\n");
        int splitIndex = -1;

        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.isEmpty())
                continue;
            if (REPLY_ATTR.matcher(t).matches()
                    || FORWARD_MARKER.matcher(t).matches()
                    || HEADER_LINE.matcher(t).matches()
                    || t.startsWith(">")
                    || t.matches("_{5,}")
                    || t.matches("(?i)-{2,}\\s*(Original Message|Forwarded message)\\s*-{2,}")) {
                splitIndex = i;
                break;
            }
        }

        if (splitIndex < 0)
            return new ThreadSplit(cleaned.trim(), "");

        String lastMessage = String.join("\n", Arrays.copyOfRange(lines, 0, splitIndex)).trim();
        String previous = String.join("\n", Arrays.copyOfRange(lines, splitIndex, lines.length)).trim();
        return new ThreadSplit(lastMessage, previous);
    }

    /**
     * Strips signature blocks (sign-offs + name/title/contact info), footer lines,
     * and confidentiality disclaimers from every message in a thread, while keeping
     * thread-separator headers (From:/Sent:/To: etc.) intact so splitting can still
     * work.
     */
    private String cleanSignaturesAndDisclaimers(String raw) {
        String[] lines = raw.replace("\r\n", "\n").split("\n");
        List<String> out = new ArrayList<>();
        boolean inSignature = false;

        for (String line : lines) {
            String t = line.strip();

            if (t.isEmpty()) {
                if (!out.isEmpty() && !out.get(out.size() - 1).isEmpty())
                    out.add("");
                continue;
            }

            if (inSignature) {
                // A new message segment (From:/Sent: etc.) ends the signature block
                if (HEADER_LINE.matcher(t).matches()
                        || FORWARD_MARKER.matcher(t).matches()
                        || REPLY_ATTR.matcher(t).matches()) {
                    inSignature = false;
                    out.add(t);
                }
                // else: still inside signature/footer — skip
                continue;
            }

            // Explicit sign-off ("Regards", "Best regards.", …) → enter signature mode
            if (SIGNATURE_START.matcher(t).matches()) {
                inSignature = true;
                continue;
            }

            // Contact-info line (Tel:, E:, W:, Mob:, …) → trim the preceding name/title
            // block then enter signature mode for any lines that follow until From:
            if (FOOTER_LINE.matcher(t).matches()) {
                trimTrailingNameBlock(out);
                inSignature = true;
                continue;
            }

            if (TRACKING_LINE.matcher(t).matches() || DISCLAIMER_LINE.matcher(t).matches())
                continue;

            out.add(t);
        }

        return out.stream()
                .collect(Collectors.joining("\n"))
                .replaceAll("\n{3,}", "\n\n")
                .strip();
    }

    /**
     * Walks backwards through accumulated output lines and removes short,
     * non-sentence
     * lines that are characteristic of name/title/company/location blocks that
     * appear
     * immediately before a contact-info line (Tel:, E:, W:, …).
     * Stops as soon as it encounters a line that looks like real message content.
     */
    private void trimTrailingNameBlock(List<String> lines) {
        int removed = 0;
        while (!lines.isEmpty() && removed < 6) {
            String last = lines.get(lines.size() - 1);
            if (last.isEmpty()) {
                lines.remove(lines.size() - 1);
                removed++;
                continue;
            }
            // Stop: looks like actual message content
            if (last.contains("?") || last.contains("!")
                    || last.endsWith(".")
                    || last.split("\\s+").length > 8
                    || last.matches("(?i)^(hi|hello|dear|hey|greetings)\\b.*")) {
                break;
            }
            lines.remove(lines.size() - 1);
            removed++;
        }
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

    private record ThreadSplit(String lastMessage, String previousMessages) {
    }

    private record KeywordMatchResult(ForwardingRule rule, String matchedKeyword) {
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

    private List<BigDecimal> processAttachments(String accessToken, String messageId,
            String emailSubject) {
        try {
            List<BigDecimal> policeReportVisas = new ArrayList<>();
            String bearerToken = getOcrBearerToken();

            JsonNode attachmentsData = outlookService.callGraphApi(
                    accessToken,
                    "messages/" + messageId + "/attachments");

            JsonNode attachments = attachmentsData.path("value");

            for (JsonNode attachment : attachments) {
                try {

                    // Only real file attachments
                    String odataType = attachment.path("@odata.type").asText("");

                    if (!"#microsoft.graph.fileAttachment".equals(odataType)) {
                        continue;
                    }

                    // Skip inline images/signatures
                    boolean isInline = attachment.path("isInline").asBoolean(false);

                    if (isInline) {
                        continue;
                    }

                    String attachmentId = attachment.path("id").asText("");
                    String fileName = attachment.path("name").asText("attachment");
                    String contentType = attachment.path("contentType")
                            .asText("application/octet-stream");

                    if (attachmentId.isEmpty()) {
                        continue;
                    }

                    // Optional: skip common signature image types
                    if (contentType.startsWith("image/")) {
                        log.info("[OCR] Skipping image attachment: {}", fileName);
                        continue;
                    }

                    JsonNode fullAttachment = outlookService.callGraphApi(
                            accessToken,
                            "messages/" + messageId + "/attachments/" + attachmentId);

                    String contentBytesBase64 = fullAttachment.path("contentBytes").asText();

                    if (contentBytesBase64 == null || contentBytesBase64.isEmpty()) {
                        continue;
                    }

                    byte[] fileBytes = Base64.getDecoder().decode(contentBytesBase64);

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
                            ocrBaseUrl + "/v1/api/ai-gateway/ocr/upload",
                            requestEntity,
                            String.class);

                    String responseBody = response.getBody();

                    if (responseBody == null) {
                        continue;
                    }

                    PoliceReportDto ocrResult = objectMapper.readValue(responseBody, PoliceReportDto.class);

                    if ("police-report".equals(
                            ocrResult.getMeta().getDocumentType())) {

                        log.info("[OCR] Police report found in attachment: {}", fileName);

                        PoliceReportAttachment policeReportAttachment = new PoliceReportAttachment(
                                ocrResult,
                                fileBytes,
                                fileName);

                        if (policeReportAttachment != null && policeReportAttachment.reportDto() != null) {
                            log.info("[POLICE REPORT] Detected police report in email: {}", emailSubject);
                            try {
                                NewNotificationByReportResponse notificationResponse = createCarNotificationByPoliceReport(
                                        policeReportAttachment.reportDto());
                                log.info("[POLICE REPORT] Successfully created car notification - ID: {}, Report: {}",
                                        notificationResponse.getNotificationId(),
                                        notificationResponse.getReportNumber());

                                policeReportVisas.add(notificationResponse.getNotificationVisa());

                                // If report number is empty, call the data management API
                                if (notificationResponse.getReportNumber() == null
                                        || notificationResponse.getReportNumber().trim().isEmpty()) {
                                    log.info(
                                            "[POLICE REPORT] Report number is empty, calling data management API for carId: {}",
                                            notificationResponse.getCarId());
                                    try {
                                        processPoliceReportAutomation(notificationResponse.getCarId(),
                                                policeReportAttachment.fileBytes(), policeReportAttachment.fileName());
                                        log.info(
                                                "[POLICE REPORT] Successfully processed police report automation for carId: {}",
                                                notificationResponse.getCarId());
                                    } catch (Exception e) {
                                        log.error(
                                                "[POLICE REPORT] Failed to process police report automation for carId: {}",
                                                notificationResponse.getCarId(), e);
                                    }
                                }

                                return policeReportVisas;

                            } catch (Exception e) {
                                log.error("[POLICE REPORT] Failed to create car notification for email: {}",
                                        emailSubject,
                                        e);
                            }
                        }
                    }

                } catch (Exception e) {
                    log.warn(
                            "[OCR] Failed to classify attachment for message {}: {}",
                            messageId,
                            e.getMessage());
                }
            }

        } catch (Exception e) {
            log.warn(
                    "[OCR] Failed to classify attachments for message {}: {}",
                    messageId,
                    e.getMessage());
        }

        return null;
    }

    private void saveFile(String attachmentName, String contentBytes, String basePath) throws Exception {

        // Skip if no content
        if (contentBytes == null || contentBytes.isEmpty()) {
            return;
        }

        // Decode Base64 content
        byte[] fileBytes = Base64.getDecoder().decode(contentBytes);

        // Sanitize filename
        String safeFileName = attachmentName.replaceAll("[\\\\/:*?\"<>|]", "_");

        Path filePath = Paths.get(basePath, safeFileName);

        // Save file
        Files.write(filePath, fileBytes);

        System.out.println("Saved attachment: " + filePath);
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

                log.info(
                        "[POLICE REPORT] Car notification created - Notification ID: {}, Visa: {}, Car ID: {}, Report Number: {}",
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

    private boolean isAuthError(Exception e) {
        String msg = e.getMessage();
        return msg != null && (msg.contains("401") || msg.contains("InvalidAuthenticationToken")
                || msg.toLowerCase().contains("token is expired"));
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
