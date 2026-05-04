package com.emailagent.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@Entity
@Table(name = "email_logs", uniqueConstraints = {
        @UniqueConstraint(name = "uk_email_logs_message_user", columnNames = {"outlook_message_id", "user_id"})
})
public class EmailLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(name = "email_from")
    private String emailFrom;

    @Column(name = "email_subject", columnDefinition = "text")
    private String emailSubject;

    @Column(name = "forwarded_to")
    private String forwardedTo;

    @Column(name = "rule_matched", columnDefinition = "uuid")
    private UUID ruleMatched;

    @Column(nullable = false)
    private String status; // forwarded, failed, no_match, skipped

    @Column(name = "outlook_message_id", columnDefinition = "text")
    private String outlookMessageId;

    @Column(name = "outlook_conversation_id", columnDefinition = "text")
    private String outlookConversationId;

    @CreationTimestamp
    @Column(name = "processed_at", updatable = false)
    private OffsetDateTime processedAt;

    @Column(name = "replied_at")
    private OffsetDateTime repliedAt;

    @Column(name = "reply_detected", nullable = false)
    private boolean replyDetected = false;

    @Column(name = "ai_classified", nullable = false)
    private boolean aiClassified = false;

    @Column(name = "ai_confidence")
    private Double aiConfidence;

    @Column(name = "ai_reasoning", columnDefinition = "text")
    private String aiReasoning;

    @Column(name = "tracking_token", columnDefinition = "uuid")
    private UUID trackingToken;

    @Column(name = "reply_source")
    private String replySource; // manual_link, sent_folder, cc_detection, manual_button

    @Column(name = "received_at")
    private OffsetDateTime receivedAt;

    @Column(name = "negative_keyword_override")
    private String negativeKeywordOverride;
}
