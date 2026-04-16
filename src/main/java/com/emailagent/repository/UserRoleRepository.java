package com.emailagent.repository;

import com.emailagent.model.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRoleRepository extends JpaRepository<UserRole, UUID> {
    Optional<UserRole> findByUserId(UUID userId);
    boolean existsByUserIdAndRole(UUID userId, String role);
}
