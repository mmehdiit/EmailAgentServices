package com.emailagent.controller;

import com.emailagent.dto.AssignDepartmentRequest;
import com.emailagent.dto.CreateDepartmentRequest;
import com.emailagent.dto.CreateUserRequest;
import com.emailagent.dto.DepartmentResponse;
import com.emailagent.dto.LoginResponse;
import com.emailagent.security.AuthenticatedUser;
import com.emailagent.service.AuthService;
import com.emailagent.service.DepartmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/api/email-agent/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AuthService authService;
    private final DepartmentService departmentService;

    @PostMapping("/users")
    public ResponseEntity<LoginResponse> createUser(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.ok(authService.createUser(request, caller.getId()));
    }

    @PostMapping("/departments")
    public ResponseEntity<DepartmentResponse> createDepartment(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @Valid @RequestBody CreateDepartmentRequest request) {
        return ResponseEntity.ok(departmentService.createDepartment(request.getName(), caller.getId()));
    }

    @GetMapping("/departments")
    public ResponseEntity<List<DepartmentResponse>> listDepartments(
            @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(departmentService.listDepartments(caller.getId()));
    }

    @PutMapping("/users/{userId}/department")
    public ResponseEntity<Void> assignUserToDepartment(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @PathVariable UUID userId,
            @Valid @RequestBody AssignDepartmentRequest request) {
        departmentService.assignUserToDepartment(userId, request.getDepartmentId(), caller.getId());
        return ResponseEntity.noContent().build();
    }
}
