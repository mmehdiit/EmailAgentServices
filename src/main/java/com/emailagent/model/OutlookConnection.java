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
@Table(name = "outlook_connections", uniqueConstraints = @UniqueConstraint(columnNames = "user_id"))
public class OutlookConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(name = "email_address")
    private String emailAddress;

    @Column(name = "access_token", columnDefinition = "text")
    private String accessToken;

    @Column(name = "refresh_token", columnDefinition = "text")
    private String refreshToken;

    @Column(name = "token_expiry")
    private OffsetDateTime tokenExpiry;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;
}
