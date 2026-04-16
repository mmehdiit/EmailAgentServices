package com.emailagent.model;

import io.hypersistence.utils.hibernate.type.array.StringArrayType;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@Entity
@Table(name = "forwarding_rules")
public class ForwardingRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(nullable = false)
    private String name;

    @Type(StringArrayType.class)
    @Column(columnDefinition = "text[]")
    private String[] keywords = new String[0];

    @Type(StringArrayType.class)
    @Column(name = "negative_keywords", columnDefinition = "text[]")
    private String[] negativeKeywords = new String[0];

    @Column(name = "recipient_email", nullable = false)
    private String recipientEmail;

    @Column(columnDefinition = "text")
    private String conditions;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private int priority = 0;

    @Column(name = "sender_pattern")
    private String senderPattern;

    @Column(name = "subject_pattern", columnDefinition = "text")
    private String subjectPattern;

    @Column(name = "ai_enabled", nullable = false)
    private boolean aiEnabled = true;

    @Column(name = "ai_context", columnDefinition = "text")
    private String aiContext;

    @Column(name = "rotation_enabled", nullable = false)
    private boolean rotationEnabled = false;

    @Column(name = "current_rotation_index", nullable = false)
    private int currentRotationIndex = 0;

    @Column(name = "smart_thread_enabled", nullable = false)
    private boolean smartThreadEnabled = true;

    @Column(name = "extract_attachments", nullable = false)
    private boolean extractAttachments = false;

    @Column(name = "template_id", columnDefinition = "uuid")
    private UUID templateId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;
}
