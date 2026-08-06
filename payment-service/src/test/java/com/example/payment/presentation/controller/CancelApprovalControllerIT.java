package com.example.payment.presentation.controller;

import com.example.payment.application.authz.AuthenticatedUser;
import com.example.payment.application.usecase.CancelApprovalUseCase;
import com.example.payment.common.exception.domain.CancelApprovalNotFoundException;
import com.example.payment.common.exception.domain.CancelNotAuthorizedException;
import com.example.payment.common.exception.domain.DuplicateCancelRequestException;
import com.example.payment.domain.entity.CancelApproval;
import com.example.payment.domain.entity.CancelApprovalStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("CancelApprovalController")
class CancelApprovalControllerIT {

    @Mock CancelApprovalUseCase useCase;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        CancelApprovalController controller = new CancelApprovalController(useCase);
        mockMvc = MockMvcBuilders
            .standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new JacksonJsonHttpMessageConverter())
            .build();
    }

    private static CancelApproval approval(long id, String paymentKey, CancelApprovalStatus status,
            String reason, String decisionReason, Long cancelRequestId) {
        return CancelApproval.reconstitute(id, 1L, paymentKey, 7L, reason, status,
            null, null, decisionReason, cancelRequestId, Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("POST cancel-requests: X-User-Id/Role/Merchant-Id 헤더가 AuthenticatedUser로 전달되고 201 + REQUESTED 반환")
    void request_maps_headers_and_returns_201() throws Exception {
        when(useCase.request(eq("pay_001"), any(), eq("고객 변심"))).thenReturn(
            approval(1L, "pay_001", CancelApprovalStatus.REQUESTED, "고객 변심", null, null));

        mockMvc.perform(post("/v1/payments/{paymentKey}/cancel-requests", "pay_001")
                .header("X-User-Role", "USER")
                .header("X-User-Id", "7")
                .header("X-Merchant-Id", "9")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reason": "고객 변심"}"""))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(1))
            .andExpect(jsonPath("$.paymentKey").value("pay_001"))
            .andExpect(jsonPath("$.status").value("REQUESTED"))
            .andExpect(jsonPath("$.requesterUserId").value(7))
            .andExpect(jsonPath("$.createdAt").exists());

        ArgumentCaptor<AuthenticatedUser> captor = ArgumentCaptor.forClass(AuthenticatedUser.class);
        verify(useCase).request(eq("pay_001"), captor.capture(), eq("고객 변심"));
        assertThat(captor.getValue()).isEqualTo(new AuthenticatedUser("7", "USER", "9"));
    }

    @Test
    @DisplayName("POST cancel-requests: reason blank면 400")
    void request_blank_reason_returns_400() throws Exception {
        mockMvc.perform(post("/v1/payments/{paymentKey}/cancel-requests", "pay_001")
                .header("X-User-Id", "7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reason": ""}"""))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST cancel-requests: 이미 진행 중인 요청이 있으면 409 (DuplicateCancelRequestException)")
    void request_duplicate_returns_409() throws Exception {
        when(useCase.request(eq("pay_001"), any(), any()))
            .thenThrow(new DuplicateCancelRequestException("이미 진행 중인 취소 승인 요청이 있습니다."));

        mockMvc.perform(post("/v1/payments/{paymentKey}/cancel-requests", "pay_001")
                .header("X-User-Id", "7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reason": "고객 변심"}"""))
            .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("GET cancel-requests?status=REQUESTED: 200 + items 매핑, ADMIN 헤더가 AuthenticatedUser로 전달")
    void list_maps_headers_and_returns_items() throws Exception {
        when(useCase.list(any(), eq(CancelApprovalStatus.REQUESTED))).thenReturn(List.of(
            approval(1L, "pay_001", CancelApprovalStatus.REQUESTED, "고객 변심", null, null),
            approval(2L, "pay_002", CancelApprovalStatus.REQUESTED, "오배송", null, null)));

        mockMvc.perform(get("/v1/cancel-requests")
                .param("status", "REQUESTED")
                .header("X-User-Role", "ADMIN")
                .header("X-User-Id", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].paymentKey").value("pay_001"))
            .andExpect(jsonPath("$.items[1].paymentKey").value("pay_002"))
            .andExpect(jsonPath("$.items[0].requesterUserId").value(7))
            .andExpect(jsonPath("$.items[0].createdAt").exists());

        ArgumentCaptor<AuthenticatedUser> captor = ArgumentCaptor.forClass(AuthenticatedUser.class);
        verify(useCase).list(captor.capture(), eq(CancelApprovalStatus.REQUESTED));
        assertThat(captor.getValue()).isEqualTo(new AuthenticatedUser("1", "ADMIN", null));
    }

    @Test
    @DisplayName("GET cancel-requests: status 파라미터 없으면 기본값 REQUESTED")
    void list_defaults_to_requested_status() throws Exception {
        when(useCase.list(any(), eq(CancelApprovalStatus.REQUESTED))).thenReturn(List.of());

        mockMvc.perform(get("/v1/cancel-requests").header("X-User-Id", "1").header("X-User-Role", "ADMIN"))
            .andExpect(status().isOk());

        verify(useCase).list(any(), eq(CancelApprovalStatus.REQUESTED));
    }

    @Test
    @DisplayName("POST {id}/approve: 200 + cancelRequestId 노출, MERCHANT 헤더 전달")
    void approve_maps_headers_and_exposes_cancelRequestId() throws Exception {
        when(useCase.approve(eq(1L), any())).thenReturn(
            approval(1L, "pay_001", CancelApprovalStatus.APPROVED, "고객 변심", null, 55L));

        mockMvc.perform(post("/v1/cancel-requests/{id}/approve", 1L)
                .header("X-User-Role", "MERCHANT")
                .header("X-User-Id", "3")
                .header("X-Merchant-Id", "9"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.cancelRequestId").value(55));

        ArgumentCaptor<AuthenticatedUser> captor = ArgumentCaptor.forClass(AuthenticatedUser.class);
        verify(useCase).approve(eq(1L), captor.capture());
        assertThat(captor.getValue()).isEqualTo(new AuthenticatedUser("3", "MERCHANT", "9"));
    }

    @Test
    @DisplayName("POST {id}/approve: 존재하지 않는 승인 요청이면 404 (CancelApprovalNotFoundException)")
    void approve_not_found_returns_404() throws Exception {
        when(useCase.approve(eq(999L), any())).thenThrow(new CancelApprovalNotFoundException(999L));

        mockMvc.perform(post("/v1/cancel-requests/{id}/approve", 999L)
                .header("X-User-Role", "ADMIN")
                .header("X-User-Id", "1"))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST {id}/approve: 인가되지 않은 사용자면 403 (CancelNotAuthorizedException)")
    void approve_unauthorized_returns_403() throws Exception {
        when(useCase.approve(eq(1L), any())).thenThrow(new CancelNotAuthorizedException());

        mockMvc.perform(post("/v1/cancel-requests/{id}/approve", 1L)
                .header("X-User-Role", "USER")
                .header("X-User-Id", "7"))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST {id}/reject: decisionReason 바디가 전달되고 200")
    void reject_maps_decisionReason_and_returns_200() throws Exception {
        when(useCase.reject(eq(1L), any(), eq("서류 미비"))).thenReturn(
            approval(1L, "pay_001", CancelApprovalStatus.REJECTED, "고객 변심", "서류 미비", null));

        mockMvc.perform(post("/v1/cancel-requests/{id}/reject", 1L)
                .header("X-User-Role", "ADMIN")
                .header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"decisionReason": "서류 미비"}"""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REJECTED"))
            .andExpect(jsonPath("$.decisionReason").value("서류 미비"));

        ArgumentCaptor<AuthenticatedUser> captor = ArgumentCaptor.forClass(AuthenticatedUser.class);
        verify(useCase).reject(eq(1L), captor.capture(), eq("서류 미비"));
        assertThat(captor.getValue()).isEqualTo(new AuthenticatedUser("1", "ADMIN", null));
    }

    @Test
    @DisplayName("POST {id}/reject: decisionReason blank면 400")
    void reject_blank_decisionReason_returns_400() throws Exception {
        mockMvc.perform(post("/v1/cancel-requests/{id}/reject", 1L)
                .header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"decisionReason": ""}"""))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST {id}/reject: 이미 결정된 요청이면 409 (DuplicateCancelRequestException 재사용)")
    void reject_already_decided_returns_409() throws Exception {
        when(useCase.reject(eq(1L), any(), any()))
            .thenThrow(new DuplicateCancelRequestException("이미 결정된 취소 승인 요청입니다."));

        mockMvc.perform(post("/v1/cancel-requests/{id}/reject", 1L)
                .header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"decisionReason": "서류 미비"}"""))
            .andExpect(status().isConflict());
    }
}
