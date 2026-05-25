package com.emailagent.dto;

import com.emailagent.model.Department;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class DepartmentResponse {
    private UUID id;
    private String name;
    private OffsetDateTime createdAt;

    public static DepartmentResponse from(Department department) {
        DepartmentResponse response = new DepartmentResponse();
        response.setId(department.getId());
        response.setName(department.getName());
        response.setCreatedAt(department.getCreatedAt());
        return response;
    }
}
