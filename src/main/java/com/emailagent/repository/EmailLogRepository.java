package com.emailagent.repository;

import com.emailagent.model.EmailLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailLogRepository extends JpaRepository<EmailLog, UUID> {

    List<EmailLog> findByUserIdOrderByProcessedAtDesc(UUID userId);

    Optional<EmailLog> findByTrackingToken(UUID trackingToken);

    Optional<EmailLog> findByOutlookMessageIdAndUserId(String outlookMessageId, UUID userId);

    boolean existsByOutlookMessageIdAndUserId(String outlookMessageId, UUID userId);

    List<EmailLog> findByUserIdAndStatusAndReplyDetectedFalseAndOutlookConversationIdIsNotNull(
            UUID userId, String status);

    @Modifying
    @Transactional
    @Query("DELETE FROM EmailLog e WHERE e.outlookMessageId = :outlookMessageId AND e.userId = :userId AND e.status IN :statuses")
    void deleteByOutlookMessageIdAndUserIdAndStatusIn(String outlookMessageId, UUID userId, List<String> statuses);

    List<EmailLog> findByUserIdAndStatus(UUID userId, String status);

    @Query("SELECT e FROM EmailLog e WHERE e.userId = :userId AND e.status = 'forwarded' ORDER BY e.processedAt DESC")
    List<EmailLog> findForwardedByUserId(UUID userId);
}
