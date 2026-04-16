package com.emailagent.repository;

import com.emailagent.model.OutlookConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OutlookConnectionRepository extends JpaRepository<OutlookConnection, UUID> {
    Optional<OutlookConnection> findByUserId(UUID userId);
    List<OutlookConnection> findAll();
    void deleteByUserId(UUID userId);
}
