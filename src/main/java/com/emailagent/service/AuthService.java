package com.emailagent.service;

import com.emailagent.dto.CreateUserRequest;
import com.emailagent.dto.LoginResponse;
import com.emailagent.exception.ApiException;
import com.emailagent.model.User;
import com.emailagent.model.UserRole;
import com.emailagent.repository.DepartmentRepository;
import com.emailagent.repository.UserRepository;
import com.emailagent.repository.UserRoleRepository;
import com.emailagent.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final DepartmentRepository departmentRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final MicrosoftTokenService microsoftTokenService;

    public LoginResponse microsoftLogin(String idToken) {
        Jwt jwt = microsoftTokenService.validateAndDecode(idToken);
        String email = microsoftTokenService.extractEmail(jwt);

        if (email == null || email.isBlank()) {
            throw ApiException.unauthorized("Cannot extract email from Microsoft token");
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    log.warn("Microsoft login attempted for unprovisioned email: {}", email);
                    return ApiException.unauthorized("Account not provisioned. Please contact your administrator.");
                });

        String role = userRoleRepository.findByUserId(user.getId())
                .map(UserRole::getRole)
                .orElse("user");

        String token = jwtTokenProvider.generateToken(user.getId(), user.getEmail());
        log.info("Microsoft login successful for {}", user.getEmail());
        return new LoginResponse(token, user.getId(), user.getEmail(), role);
    }

    @Transactional
    public LoginResponse createUser(CreateUserRequest request, UUID callerUserId) {
        if (!userRoleRepository.existsByUserIdAndRole(callerUserId, "admin")) {
            throw ApiException.forbidden("Admin access required");
        }

        String email = request.getEmail().trim().toLowerCase();

        if (userRepository.existsByEmail(email)) {
            throw ApiException.badRequest("User with this email already exists");
        }

        String assignedRole = request.getRole() != null ? request.getRole() : "user";

        if (request.getDepartmentId() != null) {
            departmentRepository.findById(request.getDepartmentId())
                    .orElseThrow(() -> ApiException.badRequest("Department not found"));

            if ("admin".equals(assignedRole) && userRepository.existsAdminInDepartment(request.getDepartmentId())) {
                throw ApiException.badRequest("Department already has an admin assigned");
            }
        }

        User user = new User();
        user.setEmail(email);
        user.setDepartmentId(request.getDepartmentId());
        userRepository.save(user);

        UserRole role = new UserRole();
        role.setUserId(user.getId());
        role.setRole(assignedRole);
        userRoleRepository.save(role);

        log.info("Provisioned user {} with role {} in department {}", user.getEmail(), role.getRole(), user.getDepartmentId());

        // No token issued here — user must authenticate via Microsoft Entra ID
        return new LoginResponse(null, user.getId(), user.getEmail(), role.getRole());
    }

    public String getUserRole(UUID userId) {
        return userRoleRepository.findByUserId(userId)
                .map(UserRole::getRole)
                .orElse("user");
    }
}
