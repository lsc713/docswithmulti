package com.example.user.presentation.controller;

import com.example.user.application.usecase.AuthUseCase;
import com.example.user.application.usecase.AuthUseCase.*;
import com.example.user.domain.entity.UserRole;
import com.example.user.presentation.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthUseCase authUseCase;

    @PostMapping("/signup")
    public ResponseEntity<TokenResponse> signup(@RequestBody @Valid SignupRequest request) {
        // D-P1-2: 클라 role/merchantId 입력 무시 — 서버가 USER/null 강제.
        TokenResult result = authUseCase.signup(new SignupCommand(
                request.email(), request.password(), request.name(),
                request.phone(), UserRole.USER, null));
        return ResponseEntity.ok(new TokenResponse(result.accessToken(), result.refreshToken()));
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@RequestBody @Valid LoginRequest request) {
        TokenResult result = authUseCase.login(new LoginCommand(request.email(), request.password()));
        return ResponseEntity.ok(new TokenResponse(result.accessToken(), result.refreshToken()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@RequestBody @Valid RefreshRequest request) {
        String accessToken = authUseCase.refresh(request.refreshToken());
        return ResponseEntity.ok(new TokenResponse(accessToken, null));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(Authentication authentication) {
        long userId = (long) authentication.getPrincipal();
        authUseCase.logout(userId);
        return ResponseEntity.ok().build();
    }
}
