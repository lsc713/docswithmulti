package com.example.user.presentation.controller;

import com.example.user.application.usecase.UserQueryUseCase;
import com.example.user.presentation.dto.MeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("MeController")
class MeControllerTest {
    MockMvc mockMvc;

    @Mock UserQueryUseCase userQuery;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new MeController(userQuery)).build();
    }

    @Test
    @DisplayName("GET /v1/auth/me — 프로필 반환, 토큰 미포함")
    void me_returnsProfile() throws Exception {
        when(userQuery.getProfile(42L)).thenReturn(new MeResponse(42L, "a@b.com", "홍길동", "USER"));

        // AuthControllerTest의 logout 테스트와 동일한 이유로 순수 Principal 람다 대신
        // Authentication mock을 사용한다 (컨트롤러 파라미터 타입이 Authentication).
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(42L);

        mockMvc.perform(get("/v1/auth/me").principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(42))
                .andExpect(jsonPath("$.email").value("a@b.com"))
                .andExpect(jsonPath("$.name").value("홍길동"))
                .andExpect(jsonPath("$.role").value("USER"));
    }
}
