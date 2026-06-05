package com.example.user.presentation.controller;

import com.example.user.application.usecase.AdminUseCase;
import com.example.user.presentation.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/v1/admin/users")
@RequiredArgsConstructor
public class AdminController {
    private final AdminUseCase adminUseCase;

    @GetMapping
    public ResponseEntity<List<AdminUserResponse>> getUsers() {
        return ResponseEntity.ok(adminUseCase.getUsers().stream().map(AdminUserResponse::from).toList());
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<AdminUserResponse> updateStatus(@PathVariable long id,
                                                           @RequestBody @Valid UpdateUserStatusRequest request) {
        return ResponseEntity.ok(AdminUserResponse.from(adminUseCase.updateStatus(id, request.status())));
    }

    @PatchMapping("/{id}/role")
    public ResponseEntity<AdminUserResponse> updateRole(@PathVariable long id,
                                                         @RequestBody @Valid UpdateUserRoleRequest request) {
        return ResponseEntity.ok(AdminUserResponse.from(adminUseCase.updateRole(id, request.role())));
    }
}
