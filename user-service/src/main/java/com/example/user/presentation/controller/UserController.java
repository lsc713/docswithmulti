package com.example.user.presentation.controller;

import com.example.user.application.usecase.UserUseCase;
import com.example.user.application.usecase.UserUseCase.*;
import com.example.user.presentation.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/users/me")
@RequiredArgsConstructor
public class UserController {
    private final UserUseCase userUseCase;

    @GetMapping
    public ResponseEntity<UserResponse> getMe(Authentication authentication) {
        long userId = (long) authentication.getPrincipal();
        return ResponseEntity.ok(UserResponse.from(userUseCase.getMe(userId)));
    }

    @PatchMapping
    public ResponseEntity<UserResponse> update(Authentication authentication,
                                                @RequestBody @Valid UpdateUserRequest request) {
        long userId = (long) authentication.getPrincipal();
        return ResponseEntity.ok(UserResponse.from(userUseCase.update(userId,
                new UpdateCommand(request.name(), request.phone()))));
    }

    @PatchMapping("/password")
    public ResponseEntity<Void> changePassword(Authentication authentication,
                                                @RequestBody @Valid ChangePasswordRequest request) {
        long userId = (long) authentication.getPrincipal();
        userUseCase.changePassword(userId, new ChangePasswordCommand(request.currentPassword(), request.newPassword()));
        return ResponseEntity.ok().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> withdraw(Authentication authentication) {
        long userId = (long) authentication.getPrincipal();
        userUseCase.withdraw(userId);
        return ResponseEntity.ok().build();
    }
}
