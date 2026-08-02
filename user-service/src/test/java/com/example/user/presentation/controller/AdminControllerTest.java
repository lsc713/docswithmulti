package com.example.user.presentation.controller;

import com.example.user.application.usecase.AdminUseCase;
import com.example.user.application.usecase.AdminUseCase.RoleChangeResult;
import com.example.user.common.exception.application.UserNotFoundException;
import com.example.user.domain.entity.UserRole;
import com.example.user.presentation.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminController")
class AdminControllerTest {

    MockMvc mockMvc;

    @Mock AdminUseCase adminUseCase;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminController(adminUseCase))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("PATCH /v1/admin/users/{id}/role — 200 + {userId, role}")
    void shouldChangeRole() throws Exception {
        when(adminUseCase.changeRole(1L, UserRole.ADMIN)).thenReturn(new RoleChangeResult(1L, UserRole.ADMIN));

        mockMvc.perform(patch("/v1/admin/users/1/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"role":"ADMIN"}
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    @DisplayName("PATCH /v1/admin/users/{id}/role — 존재하지 않는 유저 — 404")
    void shouldReturn404WhenUserNotFound() throws Exception {
        when(adminUseCase.changeRole(999L, UserRole.ADMIN)).thenThrow(new UserNotFoundException(999L));

        mockMvc.perform(patch("/v1/admin/users/999/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"role":"ADMIN"}
                            """))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PATCH /v1/admin/users/{id}/role — 알 수 없는 role 값 — 400")
    void shouldReturn400OnInvalidRoleValue() throws Exception {
        mockMvc.perform(patch("/v1/admin/users/1/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"role":"SUPERUSER"}
                            """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /v1/admin/users/{id}/role — role 누락 — 400")
    void shouldReturn400OnMissingRole() throws Exception {
        mockMvc.perform(patch("/v1/admin/users/1/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {}
                            """))
                .andExpect(status().isBadRequest());
    }
}
