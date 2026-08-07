package com.example.payment.presentation.controller;

import com.example.payment.application.interfaces.PaymentRepository;
import com.example.payment.application.service.CancelAuthorizationService;
import com.example.payment.application.service.CancelPaymentCommand;
import com.example.payment.application.usecase.CancelAuthorizationUseCase;
import com.example.payment.application.usecase.CancelPaymentUseCase;
import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.Payment;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("CancelController")
class CancelControllerTest {

    @Mock CancelPaymentUseCase cancelPaymentUseCase;
    // 기존 멱등성 테스트용: no-op(void) mock → 헤더 없이도 authorize 통과 (회귀 없음)
    @Mock CancelAuthorizationUseCase cancelAuthorizationUseCase;
    // tracer e2e 테스트용: 실제 CancelAuthorizationService 배선에 주입
    @Mock PaymentRepository paymentRepository;

    MockMvc mockMvc;
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        CancelController controller = new CancelController(cancelPaymentUseCase, cancelAuthorizationUseCase);

        mockMvc = MockMvcBuilders
            .standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
    }

    /** 실제 CancelAuthorizationService(+실제 CancelAuthorizer, mock PaymentRepository) 를 배선한 MockMvc. */
    private MockMvc mockMvcWithRealAuthz() {
        CancelController controller = new CancelController(
            cancelPaymentUseCase, new CancelAuthorizationService(paymentRepository));
        return MockMvcBuilders
            .standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
    }

    @Test
    @DisplayName("Idempotency-Key 헤더 있으면 command.idempotencyKey()에 전달")
    void idempotencyKey_header_propagates_to_command() throws Exception {
        when(cancelPaymentUseCase.cancel(any())).thenReturn(
            CancelRequest.create(1L, "hash1", BigDecimal.valueOf(30_000), "고객 변심", List.of(1L), "k1"));

        mockMvc.perform(post("/v1/payments/{paymentKey}/cancel", "pay_001")
                .header("Idempotency-Key", "k1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "cancelReason": "고객 변심",
                      "cancelItems": [{"paymentItemId": 1}]
                    }"""))
            .andExpect(status().isOk());

        ArgumentCaptor<CancelPaymentCommand> captor = ArgumentCaptor.forClass(CancelPaymentCommand.class);
        verify(cancelPaymentUseCase).cancel(captor.capture());
        assertThat(captor.getValue().idempotencyKey()).isEqualTo("k1");
    }

    @Test
    @DisplayName("Idempotency-Key 헤더 없으면 command.idempotencyKey()는 null")
    void idempotencyKey_header_absent_yields_null() throws Exception {
        when(cancelPaymentUseCase.cancel(any())).thenReturn(
            CancelRequest.create(1L, "hash2", BigDecimal.valueOf(30_000), "고객 변심", List.of(1L), null));

        mockMvc.perform(post("/v1/payments/{paymentKey}/cancel", "pay_001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "cancelReason": "고객 변심",
                      "cancelItems": [{"paymentItemId": 1}]
                    }"""))
            .andExpect(status().isOk());

        ArgumentCaptor<CancelPaymentCommand> captor = ArgumentCaptor.forClass(CancelPaymentCommand.class);
        verify(cancelPaymentUseCase).cancel(captor.capture());
        assertThat(captor.getValue().idempotencyKey()).isNull();
    }

    @Test
    @DisplayName("Idempotency-Key 256자 초과면 400 (spec §8)")
    void idempotencyKey_over_255_chars_returns_400() throws Exception {
        String tooLong = "a".repeat(256);

        mockMvc.perform(post("/v1/payments/{paymentKey}/cancel", "pay_001")
                .header("Idempotency-Key", tooLong)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "cancelReason": "고객 변심",
                      "cancelItems": [{"paymentItemId": 1}]
                    }"""))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Idempotency-Key 255자면 통과해서 command에 그대로 전달")
    void idempotencyKey_exactly_255_chars_passes() throws Exception {
        String exactly255 = "a".repeat(255);
        when(cancelPaymentUseCase.cancel(any())).thenReturn(
            CancelRequest.create(1L, "hash3", BigDecimal.valueOf(30_000), "고객 변심", List.of(1L), exactly255));

        mockMvc.perform(post("/v1/payments/{paymentKey}/cancel", "pay_001")
                .header("Idempotency-Key", exactly255)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "cancelReason": "고객 변심",
                      "cancelItems": [{"paymentItemId": 1}]
                    }"""))
            .andExpect(status().isOk());

        ArgumentCaptor<CancelPaymentCommand> captor = ArgumentCaptor.forClass(CancelPaymentCommand.class);
        verify(cancelPaymentUseCase).cancel(captor.capture());
        assertThat(captor.getValue().idempotencyKey()).isEqualTo(exactly255);
    }

    @Test
    @DisplayName("Idempotency-Key가 blank(빈 문자열)면 null로 정규화되어 fallback (spec §8)")
    void idempotencyKey_blank_header_normalizes_to_null() throws Exception {
        when(cancelPaymentUseCase.cancel(any())).thenReturn(
            CancelRequest.create(1L, "hash4", BigDecimal.valueOf(30_000), "고객 변심", List.of(1L), null));

        mockMvc.perform(post("/v1/payments/{paymentKey}/cancel", "pay_001")
                .header("Idempotency-Key", "")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "cancelReason": "고객 변심",
                      "cancelItems": [{"paymentItemId": 1}]
                    }"""))
            .andExpect(status().isOk());

        ArgumentCaptor<CancelPaymentCommand> captor = ArgumentCaptor.forClass(CancelPaymentCommand.class);
        verify(cancelPaymentUseCase).cancel(captor.capture());
        assertThat(captor.getValue().idempotencyKey()).isNull();
    }

    @Test
    @DisplayName("tracer: USER 역할 + 타인 결제 취소는 취소 코어 진입 전 403 + cancel never-invoked (P3, 정책 전환)")
    void user_role_non_owner_cancel_forbidden_before_core() throws Exception {
        // P3: USER 는 소유 여부와 무관하게 직접취소 불가 — payment 로드 없이 곧바로 403
        mockMvcWithRealAuthz().perform(post("/v1/payments/{paymentKey}/cancel", "pay_001")
                .header("X-User-Role", "USER")
                .header("X-User-Id", "42")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "cancelReason": "고객 변심",
                      "cancelItems": [{"paymentItemId": 1}]
                    }"""))
            .andExpect(status().isForbidden());

        // 무권한은 취소 플로우 진입 전에 차단 — 코어는 한 번도 호출되지 않는다
        verify(cancelPaymentUseCase, never()).cancel(any());
    }

    @Test
    @DisplayName("tracer: USER 역할은 본인 결제여도 직접취소 불가 → 403 + cancel never-invoked (P3: 승인 요청 흐름으로 전환)")
    void user_role_direct_cancel_forbidden() throws Exception {
        // P3: 체크아웃 P3 의 USER 자가취소를 승인 요청 흐름으로 대체 — 본인 결제여도 직접취소 403
        mockMvcWithRealAuthz().perform(post("/v1/payments/{paymentKey}/cancel", "pay_001")
                .header("X-User-Role", "USER")
                .header("X-User-Id", "42")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "cancelReason": "고객 변심",
                      "cancelItems": [{"paymentItemId": 1}]
                    }"""))
            .andExpect(status().isForbidden());

        verify(cancelPaymentUseCase, never()).cancel(any());
    }

    @Test
    @DisplayName("SC#1: ADMIN 취소는 인가 통과 후 기존 취소 플로우 진입 — 200 + cancel 1회 호출")
    void admin_role_cancel_passes_through_to_core() throws Exception {
        // ADMIN 은 payment 로드 생략(paymentRepository 미사용) 후 인가 통과 → 코어 cancel 도달
        when(cancelPaymentUseCase.cancel(any())).thenReturn(
            CancelRequest.create(1L, "hashAdmin", BigDecimal.valueOf(30_000), "고객 변심", List.of(1L), null));

        mockMvcWithRealAuthz().perform(post("/v1/payments/{paymentKey}/cancel", "pay_001")
                .header("X-User-Role", "ADMIN")
                .header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "cancelReason": "고객 변심",
                      "cancelItems": [{"paymentItemId": 1}]
                    }"""))
            .andExpect(status().isOk());

        // 인가 통과 → 기존 취소 플로우(멱등성·TX 불변)로 진입, cancel 정확히 1회 호출
        verify(cancelPaymentUseCase).cancel(any());
    }
}
