package com.emailagent.service;

import com.emailagent.dto.DepartmentResponse;
import com.emailagent.exception.ApiException;
import com.emailagent.model.Department;
import com.emailagent.model.User;
import com.emailagent.repository.DepartmentRepository;
import com.emailagent.repository.UserRepository;
import com.emailagent.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DepartmentService {

    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;

    @Transactional
    public DepartmentResponse createDepartment(String name, UUID callerUserId) {
        requireAdmin(callerUserId);

        if (departmentRepository.existsByName(name)) {
            throw ApiException.badRequest("Department with this name already exists");
        }

        Department department = new Department();
        department.setName(name);
        departmentRepository.save(department);

        log.info("Created department '{}' by user {}", name, callerUserId);
        return DepartmentResponse.from(department);
    }

    public List<DepartmentResponse> listDepartments(UUID callerUserId) {
        requireAdmin(callerUserId);
        return departmentRepository.findAll().stream()
                .map(DepartmentResponse::from)
                .toList();
    }

    @Transactional
    public void assignUserToDepartment(UUID targetUserId, UUID departmentId, UUID callerUserId) {
        requireAdmin(callerUserId);

        departmentRepository.findById(departmentId)
                .orElseThrow(() -> ApiException.notFound("Department not found"));

        User user = userRepository.findById(targetUserId)
                .orElseThrow(() -> ApiException.notFound("User not found"));

        String targetRole = userRoleRepository.findByUserId(targetUserId)
                .map(ur -> ur.getRole())
                .orElse("user");

        if ("admin".equals(targetRole)) {
            Optional<User> existingAdmin = userRepository.findAdminByDepartmentId(departmentId);
            if (existingAdmin.isPresent() && !existingAdmin.get().getId().equals(targetUserId)) {
                throw ApiException.badRequest("Department already has an admin assigned");
            }
        }

        user.setDepartmentId(departmentId);
        userRepository.save(user);

        log.info("Assigned user {} (role={}) to department {}", targetUserId, targetRole, departmentId);
    }

    private void requireAdmin(UUID userId) {
        if (!userRoleRepository.existsByUserIdAndRole(userId, "admin")) {
            throw ApiException.forbidden("Admin access required");
        }
    }
}
