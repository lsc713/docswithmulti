package com.example.riskmanagement.infrastructure.http;

import com.example.riskmanagement.application.exception.ServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.client.RestClient;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MerchantLimitRestClient")
class MerchantLimitRestClientTest {

    @Mock
    RestClient mockRestClient;

    @Mock
    RestClient.RequestHeadersUriSpec<?> uriSpec;

    @Mock
    RestClient.RequestHeadersSpec<?> headersSpec;

    @Mock
    RestClient.ResponseSpec responseSpec;

    MerchantLimitRestClient sut;

    @BeforeEach
    void setUp() {
        when(mockRestClient.get()).thenReturn((RestClient.RequestHeadersUriSpec) uriSpec);
        when(uriSpec.uri(anyString(), anyLong())).thenReturn((RestClient.RequestHeadersSpec) headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(), any())).thenReturn(responseSpec);

        sut = new MerchantLimitRestClient(mockRestClient);
    }

    @Test
    @DisplayName("fetchDailyLimit 성공 시 dailyLimit 반환")
    void fetch_daily_limit_success() {
        // given
        MerchantLimitResponse response = new MerchantLimitResponse(1L, new BigDecimal("1000000"), "ACTIVE");
        when(responseSpec.body(MerchantLimitResponse.class)).thenReturn(response);

        // when
        BigDecimal result = sut.fetchDailyLimit(1L, LocalDate.of(2026, 4, 28));

        // then
        assertThat(result).isEqualByComparingTo(new BigDecimal("1000000"));
    }

    @Test
    @DisplayName("fetchDailyLimit 응답이 null이면 ServiceUnavailableException 발생")
    void fetch_daily_limit_throws_service_unavailable_when_response_is_null() {
        // given
        when(responseSpec.body(MerchantLimitResponse.class)).thenReturn(null);

        // when & then
        assertThatThrownBy(() -> sut.fetchDailyLimit(1L, LocalDate.of(2026, 4, 28)))
            .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    @DisplayName("fallback 호출 시 ServiceUnavailableException 발생")
    void fallback_throws_service_unavailable() throws Exception {
        Method method = MerchantLimitRestClient.class.getDeclaredMethod(
            "fetchDailyLimitFallback", long.class, LocalDate.class, Exception.class);
        method.setAccessible(true);

        assertThatThrownBy(() -> {
            try {
                method.invoke(sut, 1L, LocalDate.of(2026, 4, 28), new RuntimeException("cb open"));
            } catch (InvocationTargetException ex) {
                throw ex.getCause();
            }
        }).isInstanceOf(ServiceUnavailableException.class);
    }
}
