package com.emailagent.service;

import com.emailagent.dto.CreateUserRequest;
import com.emailagent.dto.LoginRequest;
import com.emailagent.dto.LoginResponse;
import com.emailagent.exception.ApiException;
import com.emailagent.model.User;
import com.emailagent.model.UserRole;
import com.emailagent.repository.UserRepository;
import com.emailagent.repository.UserRoleRepository;
import com.emailagent.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> ApiException.unauthorized("Invalid email or password"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw ApiException.unauthorized("Invalid email or password");
        }

        String role = userRoleRepository.findByUserId(user.getId())
                .map(UserRole::getRole)
                .orElse("user");

        String token = jwtTokenProvider.generateToken(user.getId(), user.getEmail());
        return new LoginResponse(token, user.getId(), user.getEmail(), role);
    }

    @Transactional
    public LoginResponse createUser(CreateUserRequest request, UUID callerUserId) {
        // Verify caller is admin
        if (!userRoleRepository.existsByUserIdAndRole(callerUserId, "admin")) {
            throw ApiException.forbidden("Admin access required");
        }

        if (userRepository.existsByEmail(request.getEmail())) {
            throw ApiException.badRequest("User with this email already exists");
        }

        User user = new User();
        user.setEmail(request.getEmail());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        userRepository.save(user);

        UserRole role = new UserRole();
        role.setUserId(user.getId());
        role.setRole(request.getRole() != null ? request.getRole() : "user");
        userRoleRepository.save(role);

        log.info("Created user {} with role {}", user.getEmail(), role.getRole());

        String token = jwtTokenProvider.generateToken(user.getId(), user.getEmail());
        return new LoginResponse(token, user.getId(), user.getEmail(), role.getRole());
    }

    public String getUserRole(UUID userId) {
        return userRoleRepository.findByUserId(userId)
                .map(UserRole::getRole)
                .orElse("user");
    }
}
