package com.example.user.presentation.controller;

import com.example.user.application.usecase.AuthUseCase;
import com.example.user.application.usecase.AuthUseCase.TokenResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthController")
class AuthControllerTest {

    MockMvc mockMvc;
    ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    AuthUseCase authUseCase;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AuthController(authUseCase))
                .build();
    }

    @Test
    @DisplayName("POST /v1/auth/signup — 토큰 반환")
    void shouldSignup() throws Exception {
        when(authUseCase.signup(any())).thenReturn(new TokenResult("access", "refresh"));
        mockMvc.perform(post("/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"email":"test@example.com","password":"pw123",
                             "name":"홍길동","phone":"010-1234-5678"}
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access"))
                .andExpect(jsonPath("$.refreshToken").value("refresh"));
    }

    @Test
    @DisplayName("POST /v1/auth/login — 토큰 반환")
    void shouldLogin() throws Exception {
        when(authUseCase.login(any())).thenReturn(new TokenResult("access", "refresh"));
        mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"email":"test@example.com","password":"pw123"}
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access"));
    }

    @Test
    @DisplayName("POST /v1/auth/refresh — 새 Access Token")
    void shouldRefresh() throws Exception {
        when(authUseCase.refresh("valid-refresh")).thenReturn("new-access");
        mockMvc.perform(post("/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"refreshToken":"valid-refresh"}
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-access"));
    }
}
