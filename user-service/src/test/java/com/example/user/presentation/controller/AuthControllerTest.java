package com.example.user.presentation.controller;

import com.example.user.application.usecase.AuthUseCase;
import com.example.user.application.usecase.AuthUseCase.TokenResult;
import com.example.user.presentation.support.AuthCookieFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
        AuthCookieFactory cookies = new AuthCookieFactory(true, null, 1000L * 60 * 60 * 24 * 14);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AuthController(authUseCase, cookies))
                .build();
    }

    @Test
    @DisplayName("POST /v1/auth/signup — 쿠키 발급 + body에 토큰 없음")
    void shouldSignup() throws Exception {
        when(authUseCase.signup(any())).thenReturn(new TokenResult("access", "refresh"));
        mockMvc.perform(post("/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"email":"test@example.com","password":"pw123",
                             "name":"홍길동","phone":"010-1234-5678"}
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("OK"))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(cookie().value("access_token", "access"))
                .andExpect(cookie().value("refresh_token", "refresh"));
    }

    @Test
    @DisplayName("POST /v1/auth/login — 쿠키 발급 + body에 토큰 없음")
    void loginSetsCookies() throws Exception {
        when(authUseCase.login(any())).thenReturn(new TokenResult("jwt-access", "rt-uuid"));
        mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"email":"test@example.com","password":"pw123"}
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("OK"))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(cookie().value("access_token", "jwt-access"))
                .andExpect(cookie().httpOnly("access_token", true))
                .andExpect(cookie().value("refresh_token", "rt-uuid"))
                .andExpect(cookie().httpOnly("csrf_token", false))
                .andExpect(header().stringValues("Set-Cookie",
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("SameSite=Lax"))));
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

    @Test
    @DisplayName("POST /v1/auth/logout — 쿠키 만료(Max-Age=0)")
    void logoutExpiresCookies() throws Exception {
        // 컨트롤러가 authentication.getPrincipal()을 (long) 캐스팅하므로,
        // MockMvc의 .principal(Principal)은 파라미터 타입 Authentication과 instanceof가 맞아야 해서
        // 순수 Principal 람다가 아닌 Authentication mock으로 principal=42L을 세팅한다.
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(42L);

        mockMvc.perform(post("/v1/auth/logout").principal(authentication))
                .andExpect(status().isOk())
                .andExpect(cookie().maxAge("access_token", 0));
    }
}
