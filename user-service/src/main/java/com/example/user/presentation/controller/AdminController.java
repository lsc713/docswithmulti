package com.example.user.presentation.controller;

import com.example.user.application.usecase.AdminUseCase;
import com.example.user.application.usecase.AdminUseCase.RoleChangeResult;
import com.example.user.application.usecase.UserQueryUseCase;
import com.example.user.presentation.dto.ChangeRoleRequest;
import com.example.user.presentation.dto.UserListResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// 인가: SecurityConfig가 /v1/admin/** 전체를 hasRole("ADMIN")으로 게이트 — 여기서 별도 인가 체크 불필요.
@RestController
@RequestMapping("/v1/admin/users")
public class AdminController {
    private final AdminUseCase adminUseCase;
    private final UserQueryUseCase userQuery;

    public AdminController(AdminUseCase adminUseCase, UserQueryUseCase userQuery) {
        this.adminUseCase = adminUseCase;
        this.userQuery = userQuery;
    }

    @GetMapping
    public UserListResponse list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return userQuery.listUsers(page, size);
    }

    @PatchMapping("/{userId}/role")
    public Map<String, Object> changeRole(@PathVariable long userId, @RequestBody @Valid ChangeRoleRequest request) {
        RoleChangeResult result = adminUseCase.changeRole(userId, request.role());
        return Map.of("userId", result.userId(), "role", result.role().name());
    }
}
