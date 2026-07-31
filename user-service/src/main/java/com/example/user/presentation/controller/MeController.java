package com.example.user.presentation.controller;

import com.example.user.application.usecase.UserQueryUseCase;
import com.example.user.presentation.dto.MeResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/auth")
public class MeController {
    private final UserQueryUseCase userQuery;

    public MeController(UserQueryUseCase userQuery) {
        this.userQuery = userQuery;
    }

    @GetMapping("/me")
    public MeResponse me(Authentication authentication) {
        long userId = Long.parseLong(String.valueOf(authentication.getPrincipal()));
        return userQuery.getProfile(userId);
    }
}
