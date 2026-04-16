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
@Table(name = "email_templates")
public class EmailTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "user_id", columnDefinition = "uuid")
    private UUID userId;

    @Column(nullable = false)
    private String name;

    @Column(name = "subject_template", columnDefinition = "text")
    private String subjectTemplate;

    @Column(name = "body_template", columnDefinition = "text")
    private String bodyTemplate;

    @Column(name = "font_family")
    private String fontFamily = "Arial";

    @Column(name = "primary_color")
    private String primaryColor = "#0C799A";

    @Column(name = "text_color")
    private String textColor = "#333333";

    @Column(name = "background_color")
    private String backgroundColor = "#ffffff";

    @Column(name = "header_image_url", columnDefinition = "text")
    private String headerImageUrl;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;
}
