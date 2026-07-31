package com.example.user.presentation.controller;

import com.example.user.application.usecase.AuthUseCase;
import com.example.user.application.usecase.AuthUseCase.*;
import com.example.user.domain.entity.UserRole;
import com.example.user.presentation.dto.*;
import com.example.user.presentation.support.AuthCookieFactory;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v1/auth")
public class AuthController {
    private final AuthUseCase authUseCase;
    private final AuthCookieFactory cookies;

    public AuthController(AuthUseCase authUseCase, AuthCookieFactory cookies) {
        this.authUseCase = authUseCase;
        this.cookies = cookies;
    }

    @PostMapping("/signup")
    public ResponseEntity<Map<String, String>> signup(@RequestBody @Valid SignupRequest request) {
        // D-P1-2: 클라 role/merchantId 입력 무시 — 서버가 USER/null 강제.
        TokenResult result = authUseCase.signup(new SignupCommand(
                request.email(), request.password(), request.name(),
                request.phone(), UserRole.USER, null));
        return issue(result);
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(@RequestBody @Valid LoginRequest request) {
        TokenResult result = authUseCase.login(new LoginCommand(request.email(), request.password()));
        return issue(result);
    }

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, String>> refresh(@CookieValue("refresh_token") String refreshToken) {
        String accessToken = authUseCase.refresh(refreshToken);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.SET_COOKIE, cookies.access(accessToken).toString());
        return ResponseEntity.ok().headers(headers).body(Map.of("result", "OK"));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(Authentication authentication) {
        long userId = Long.parseLong(String.valueOf(authentication.getPrincipal()));
        authUseCase.logout(userId);
        HttpHeaders headers = new HttpHeaders();
        cookies.expireAll().forEach(c -> headers.add(HttpHeaders.SET_COOKIE, c.toString()));
        return ResponseEntity.ok().headers(headers).build();
    }

    private ResponseEntity<Map<String, String>> issue(TokenResult result) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.SET_COOKIE, cookies.access(result.accessToken()).toString());
        headers.add(HttpHeaders.SET_COOKIE, cookies.refresh(result.refreshToken()).toString());
        headers.add(HttpHeaders.SET_COOKIE, cookies.csrf(cookies.newCsrfValue()).toString());
        return ResponseEntity.ok().headers(headers).body(Map.of("result", "OK"));
    }
}
