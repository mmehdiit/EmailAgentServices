package com.emailagent.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.emailagent.exception.ApiException;
import com.emailagent.model.EmailLog;
import com.emailagent.model.OutlookConnection;
import com.emailagent.repository.EmailLogRepository;
import com.emailagent.repository.OutlookConnectionRepository;
import com.fasterxml.jackson.databind.JsonNode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReplyCheckingService {

    private final OutlookConnectionRepository connectionRepository;
    private final EmailLogRepository emailLogRepository;
    private final OutlookService outlookService;

    @Value("${app.reply-check.enabled}")
    private boolean processingEnabled;

    @Scheduled(cron = "${app.reply-check.cron}")
    public void scheduledCheckReplies() {
        if (!processingEnabled) {
            log.info("Scheduled reply detection is disabled. Skipping...");
            return;
        }

        log.info("Starting scheduled reply detection...");
        checkRepliesForAllUsers();
    }

    @Transactional
    public void checkRepliesForAllUsers() {
        List<OutlookConnection> connections = connectionRepository.findAll();
        int totalChecked = 0, repliesDetected = 0;

        for (OutlookConnection connection : connections) {
            try {
                int[] result = checkRepliesForUser(connection);
                totalChecked += result[0];
                repliesDetected += result[1];
            } catch (Exception e) {
                log.error("Error checking replies for user {}", connection.getUserId(), e);
            }
        }

        log.info("Reply check complete. Checked: {}, Detected: {}", totalChecked, repliesDetected);
    }

    @Transactional
    public int[] checkRepliesForUser(OutlookConnection connection) {
        UUID userId = connection.getUserId();

        String accessToken;
        if (connection.getTokenExpiry().isBefore(OffsetDateTime.now().plusMinutes(5))) {
            accessToken = outlookService.refreshAccessToken(connection);
        } else {
            accessToken = connection.getAccessToken();
        }

        // Get forwarded emails without replies
        List<EmailLog> pendingLogs = emailLogRepository
                .findByUserIdAndStatusAndReplyDetectedFalseAndOutlookConversationIdIsNotNull(userId, "forwarded");

        if (pendingLogs.isEmpty())
            return new int[] { 0, 0 };

        log.info("Checking {} forwarded emails for replies for user {}", pendingLogs.size(), userId);

        // Get sent items from last 7 days
        String sevenDaysAgo = OffsetDateTime.now(ZoneOffset.UTC).minusDays(7).truncatedTo(ChronoUnit.SECONDS)
                .toString();
        JsonNode sentData = outlookService.callGraphApi(accessToken,
                "mailFolders/sentitems/messages?$filter=sentDateTime ge " + sevenDaysAgo +
                        "&$top=100&$orderby=sentDateTime desc&$select=id,toRecipients,sentDateTime,conversationId");

        JsonNode sentItems = sentData.path("value");
        int checked = pendingLogs.size(), detected = 0;

        // Check each pending log against sent items
        for (EmailLog pending : pendingLogs) {
            for (JsonNode sent : sentItems) {
                JsonNode toRecipients = sent.path("toRecipients");
                boolean recipientMatch = false;
                for (JsonNode r : toRecipients) {
                    if (pending.getEmailFrom() != null &&
                            pending.getEmailFrom()
                                    .equalsIgnoreCase(r.path("emailAddress").path("address").asText(""))) {
                        recipientMatch = true;
                        break;
                    }
                }
                boolean conversationMatch = pending.getOutlookConversationId() != null &&
                        pending.getOutlookConversationId().equals(sent.path("conversationId").asText(""));

                if (recipientMatch && conversationMatch) {
                    pending.setReplyDetected(true);
                    String sentDateTime = sent.path("sentDateTime").asText(null);
                    pending.setRepliedAt(
                            sentDateTime != null ? OffsetDateTime.parse(sentDateTime) : OffsetDateTime.now());
                    pending.setReplySource("sent_folder");
                    emailLogRepository.save(pending);
                    detected++;
                    log.info("Reply detected via sent folder for log {}", pending.getId());
                    break;
                }
            }
        }

        // Also check CC replies in inbox
        String integratedEmail = connection.getEmailAddress() != null ? connection.getEmailAddress().toLowerCase() : "";
        if (!integratedEmail.isEmpty()) {
            JsonNode inboxData = outlookService.callGraphApi(accessToken,
                    "mailFolders/inbox/messages?$filter=receivedDateTime ge " + sevenDaysAgo +
                            "&$top=100&$orderby=receivedDateTime desc&$select=id,from,toRecipients,ccRecipients,receivedDateTime");

            for (JsonNode email : inboxData.path("value")) {
                JsonNode ccRecipients = email.path("ccRecipients");
                boolean isCC = false;
                for (JsonNode cc : ccRecipients) {
                    if (integratedEmail.equals(cc.path("emailAddress").path("address").asText("").toLowerCase())) {
                        isCC = true;
                        break;
                    }
                }
                if (!isCC)
                    continue;

                for (JsonNode toRec : email.path("toRecipients")) {
                    String toEmail = toRec.path("emailAddress").path("address").asText("").toLowerCase();
                    for (EmailLog pending : pendingLogs) {
                        if (!pending.isReplyDetected() && pending.getEmailFrom() != null &&
                                pending.getEmailFrom().toLowerCase().equals(toEmail)) {
                            pending.setReplyDetected(true);
                            String receivedAt = email.path("receivedDateTime").asText(null);
                            pending.setRepliedAt(
                                    receivedAt != null ? OffsetDateTime.parse(receivedAt) : OffsetDateTime.now());
                            pending.setReplySource("cc_detection");
                            emailLogRepository.save(pending);
                            detected++;
                            log.info("Reply detected via CC for log {}", pending.getId());
                            break;
                        }
                    }
                }
            }
        }

        return new int[] { checked, detected };
    }

    @Transactional
    public void markReply(UUID trackingToken, boolean isPreview, Object[] result) {
        Optional<EmailLog> logOpt = emailLogRepository.findByTrackingToken(trackingToken);
        if (logOpt.isEmpty()) {
            result[0] = "not_found";
            return;
        }

        EmailLog emailLog = logOpt.get();

        if (isPreview) {
            result[0] = emailLog.isReplyDetected() ? "already_replied" : "pending";
            result[1] = emailLog.getEmailSubject();
            return;
        }

        if (emailLog.isReplyDetected()) {
            result[0] = "already_replied";
            result[1] = emailLog.getEmailSubject();
            return;
        }

        emailLog.setReplyDetected(true);
        emailLog.setRepliedAt(OffsetDateTime.now());
        emailLog.setReplySource("manual_link");
        emailLogRepository.save(emailLog);

        result[0] = "success";
        result[1] = emailLog.getEmailSubject();
        log.info("Reply marked via tracking link for log {}", emailLog.getId());
    }

    @Transactional
    public void markReplyManual(UUID emailLogId, UUID userId) {
        EmailLog log = emailLogRepository.findById(emailLogId)
                .orElseThrow(() -> ApiException.notFound("Email log not found"));

        if (!log.getUserId().equals(userId)) {
            throw ApiException.forbidden("Not authorized");
        }

        log.setReplyDetected(true);
        log.setRepliedAt(OffsetDateTime.now());
        log.setReplySource("manual_button");
        emailLogRepository.save(log);
    }
}
