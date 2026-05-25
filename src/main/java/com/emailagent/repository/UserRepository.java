package com.emailagent.repository;

import com.emailagent.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);

    @Query("SELECT u FROM User u WHERE u.departmentId = :departmentId AND EXISTS " +
           "(SELECT ur FROM UserRole ur WHERE ur.userId = u.id AND ur.role = 'admin')")
    Optional<User> findAdminByDepartmentId(@Param("departmentId") UUID departmentId);

    @Query("SELECT COUNT(u) > 0 FROM User u WHERE u.departmentId = :departmentId AND EXISTS " +
           "(SELECT ur FROM UserRole ur WHERE ur.userId = u.id AND ur.role = 'admin')")
    boolean existsAdminInDepartment(@Param("departmentId") UUID departmentId);
}
