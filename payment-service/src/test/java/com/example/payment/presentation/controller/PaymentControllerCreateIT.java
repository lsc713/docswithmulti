package com.example.payment.presentation.controller;

import com.example.payment.application.service.CreatePaymentCommand;
import com.example.payment.application.usecase.CreatePaymentUseCase;
import com.example.payment.application.usecase.CreatePaymentUseCase.Result;
import com.example.payment.application.usecase.PaymentExistsQuery;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.PaymentStatus;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /v1/payments 컨트롤러 IT — 소유자가 body userId가 아닌 X-User-Id 헤더에서 온다는 것을 증명 (TRUST-01).
 * standalone MockMvc — order-service OrderControllerIT/CancelControllerTest 패턴 미러.
 * createPaymentUseCase는 mock — 이 IT는 헤더 매핑만 검증(검증/재고 로직 아님).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentController - POST /v1/payments")
class PaymentControllerCreateIT {

    @Mock CreatePaymentUseCase createPaymentUseCase;
    @Mock PaymentExistsQuery paymentExistsQuery;

    MockMvc mockMvc;
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // Spring Boot 자동설정 ObjectMapper와 동일하게 알 수 없는 필드는 무시(프로덕션 기본값 미러).
        objectMapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        PaymentController controller = new PaymentController(createPaymentUseCase, paymentExistsQuery);
        mockMvc = MockMvcBuilders
            .standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
    }

    private static Result stubResult() {
        Payment payment = Payment.reconstruct(
            1L, "pay_abc", 1L, 42L, "TOSS",
            BigDecimal.valueOf(30_000), "KRW", 90,
            PaymentStatus.COMPLETED, LocalDateTime.now(), LocalDateTime.now());
        return new Result(payment, List.of());
    }

    private static String requestBody() {
        return """
            {"merchantId":1,"pgType":"TOSS","cancelPeriodDays":90,
             "items":[{"orderItemId":10,"productId":200,"itemName":"상품A","itemAmount":30000,"skuId":500,"quantity":2}]}
            """;
    }

    @Test
    @DisplayName("X-User-Id 헤더값이 소유자로 커맨드에 전달된다 (TRUST-01)")
    void create_ownerComesFromXUserIdHeader() throws Exception {
        when(createPaymentUseCase.create(any())).thenReturn(stubResult());

        mockMvc.perform(post("/v1/payments")
                .header("X-User-Id", "42")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody()))
            .andExpect(status().isOk());

        ArgumentCaptor<CreatePaymentCommand> captor = ArgumentCaptor.forClass(CreatePaymentCommand.class);
        verify(createPaymentUseCase).create(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("회귀: body에 userId를 실어 보내도 무시되고 헤더값만 소유자로 쓰인다 (TRUST-01)")
    void create_bodyUserId_ifSent_isIgnored() throws Exception {
        when(createPaymentUseCase.create(any())).thenReturn(stubResult());

        // CreatePaymentRequest에 존재하지도 않는 userId 필드에 값을 실어 보내는 스푸핑 시도 —
        // 알 수 없는 JSON 필드는 역직렬화 시 무시된다(Jackson 기본 FAIL_ON_UNKNOWN_PROPERTIES=false).
        mockMvc.perform(post("/v1/payments")
                .header("X-User-Id", "42")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"userId":999,"merchantId":1,"pgType":"TOSS","cancelPeriodDays":90,
                     "items":[{"orderItemId":10,"productId":200,"itemName":"상품A","itemAmount":30000,"skuId":500,"quantity":2}]}
                    """))
            .andExpect(status().isOk());

        ArgumentCaptor<CreatePaymentCommand> captor = ArgumentCaptor.forClass(CreatePaymentCommand.class);
        verify(createPaymentUseCase).create(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("X-User-Id 헤더 누락 → 생성 유스케이스 미호출(바인딩 실패로 단락)")
    void create_missingXUserIdHeader_useCaseNotInvoked() throws Exception {
        // standalone MockMvc(GlobalExceptionHandler만 부착, 전체 Spring Boot 컨텍스트 아님)에서는
        // MissingRequestHeaderException이 범용 Exception 핸들러(500)로 떨어진다 — 실제 배포 환경(전체
        // DispatcherServlet)에서는 400. 이 IT의 관심사는 상태코드 세부가 아니라 "헤더 없이는 유스케이스가
        // 절대 호출되지 않는다"는 소유자 결정 계약(TRUST-01)이므로 2xx가 아님만 단언한다.
        mockMvc.perform(post("/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .status().is(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR.value()));

        verifyNoInteractions(createPaymentUseCase);
    }
}
