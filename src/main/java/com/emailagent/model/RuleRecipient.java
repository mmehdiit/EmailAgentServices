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
@Table(name = "rule_recipients")
public class RuleRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "rule_id", nullable = false, columnDefinition = "uuid")
    private UUID ruleId;

    @Column(nullable = false)
    private String email;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(name = "is_on_vacation", nullable = false)
    private boolean isOnVacation = false;

    @Column(name = "vacation_start")
    private OffsetDateTime vacationStart;

    @Column(name = "vacation_end")
    private OffsetDateTime vacationEnd;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;
}
