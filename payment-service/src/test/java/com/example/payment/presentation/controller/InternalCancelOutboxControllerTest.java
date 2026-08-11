package com.example.payment.presentation.controller;

import com.example.payment.application.authz.InternalOperatorAccess;
import com.example.payment.application.exception.ActiveRedriveExistsException;
import com.example.payment.application.exception.CancelOutboxNotFoundException;
import com.example.payment.application.exception.CancelOutboxRedriveNotFoundException;
import com.example.payment.application.interfaces.CancelOutboxRedriveRepository;
import com.example.payment.application.model.CancelOutboxDecision;
import com.example.payment.application.model.CancelRestoreLegSnapshot;
import com.example.payment.application.model.CancelRestoreLegStatus;
import com.example.payment.application.service.CancelOutboxRedriveService;
import com.example.payment.application.service.CancelOutboxRedriveTelemetry;
import com.example.payment.application.usecase.CancelOutboxInspectionUseCase;
import com.example.payment.domain.entity.CancelOutboxRedrive;
import com.example.payment.domain.entity.CancelOutboxRedriveFailureStage;
import com.example.payment.domain.entity.CancelOutboxRedriveStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class InternalCancelOutboxControllerTest {

    @Mock
    private CancelOutboxInspectionUseCase useCase;

    @Mock
    private CancelOutboxRedriveRepository redriveRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        var redriveService = new CancelOutboxRedriveService(
            redriveRepository,
            org.mockito.Mockito.mock(CancelOutboxRedriveTelemetry.class),
            Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC));
        var objectMapper = JsonMapper.builder().build();
        var controller = new InternalCancelOutboxController(
            useCase, redriveService, redriveService, new InternalOperatorAccess(), objectMapper);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new JacksonJsonHttpMessageConverter(objectMapper))
            .build();
    }

    @Test
    void adminOperatorCanRequestRedriveAsynchronouslyWithoutInspectionOrReplayDependency() throws Exception {
        String reason = "  장애 복구  ";
        when(redriveRepository.createRequested(
            41L, "operator-1", reason, Instant.parse("2026-08-11T00:00:00Z")))
            .thenReturn(redrive(7L, 41L, CancelOutboxRedriveStatus.REQUESTED, null,
                "operator-1", reason, Instant.parse("2026-08-11T00:00:00Z"),
                null, null, null, null, null, null));

        long startedAt = System.nanoTime();
        mockMvc.perform(post("/internal/cancel-outbox/41/redrives")
                .header("X-User-Role", "ADMIN")
                .header("X-User-Id", "operator-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"  장애 복구  \"}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.redriveId").value(7))
            .andExpect(jsonPath("$.sourceOutboxId").value(41))
            .andExpect(jsonPath("$.status").value("REQUESTED"))
            .andExpect(jsonPath("$.requestedBy").value("operator-1"))
            .andExpect(jsonPath("$.reason").value("  장애 복구  "))
            .andExpect(jsonPath("$.failureStage").value(nullValue()));

        org.assertj.core.api.Assertions.assertThat(System.nanoTime() - startedAt)
            .isLessThan(500_000_000L);
        verifyNoInteractions(useCase);
    }

    @Test
    void adminOperatorCanGetRedriveWithExplicitNullOptionalFields() throws Exception {
        when(redriveRepository.findById(7L)).thenReturn(java.util.Optional.of(redrive(
            7L, 41L, CancelOutboxRedriveStatus.REQUESTED, null,
            "operator-1", "  장애 복구  ", Instant.parse("2026-08-11T00:00:00Z"),
            null, null, null, null, null, null)));

        mockMvc.perform(get("/internal/cancel-outbox/redrives/7")
                .header("X-User-Role", "ADMIN")
                .header("X-User-Id", "operator-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.redriveId").value(7))
            .andExpect(jsonPath("$.sourceOutboxId").value(41))
            .andExpect(jsonPath("$.status").value("REQUESTED"))
            .andExpect(jsonPath("$.failureStage").value(nullValue()))
            .andExpect(jsonPath("$.requestedBy").value("operator-1"))
            .andExpect(jsonPath("$.reason").value("  장애 복구  "))
            .andExpect(jsonPath("$.requestedAt").value("2026-08-11T00:00:00Z"))
            .andExpect(jsonPath("$.startedAt").value(nullValue()))
            .andExpect(jsonPath("$.completedAt").value(nullValue()))
            .andExpect(jsonPath("$.result").value(nullValue()))
            .andExpect(jsonPath("$.lastError").value(nullValue()))
            .andExpect(jsonPath("$.beforeState").value(nullValue()))
            .andExpect(jsonPath("$.afterState").value(nullValue()))
            .andExpect(jsonPath("$.payload").doesNotExist())
            .andExpect(jsonPath("$.paymentKey").doesNotExist());
    }

    @Test
    void adminOperatorGetsStoredAuditJsonAsObjectsInsteadOfEncodedStrings() throws Exception {
        when(redriveRepository.findById(8L)).thenReturn(java.util.Optional.of(redrive(
            8L, 42L, CancelOutboxRedriveStatus.RESOLVED, null,
            "operator-1", "replay", Instant.parse("2026-08-11T00:00:00Z"),
            Instant.parse("2026-08-11T00:00:01Z"), Instant.parse("2026-08-11T00:00:03Z"),
            "{\"topic\":\"payment.cancelled\",\"partition\":0,\"offset\":12}", null,
            "{\"decision\":\"REDRIVE_REQUIRED\",\"order\":{\"status\":\"NOT_APPLIED\"}}",
            "{\"decision\":\"ALREADY_APPLIED\",\"order\":{\"status\":\"APPLIED\"}}")));

        mockMvc.perform(get("/internal/cancel-outbox/redrives/8")
                .header("X-User-Role", "ADMIN")
                .header("X-User-Id", "operator-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.result.topic").value("payment.cancelled"))
            .andExpect(jsonPath("$.result.partition").value(0))
            .andExpect(jsonPath("$.beforeState.order.status").value("NOT_APPLIED"))
            .andExpect(jsonPath("$.afterState.order.status").value("APPLIED"));
    }

    @Test
    void corruptStoredAuditJsonFailsTheRequestInsteadOfReturningAString() throws Exception {
        when(redriveRepository.findById(9L)).thenReturn(java.util.Optional.of(redrive(
            9L, 43L, CancelOutboxRedriveStatus.REDRIVING, null,
            "operator-1", "replay", Instant.parse("2026-08-11T00:00:00Z"),
            Instant.parse("2026-08-11T00:00:01Z"), null,
            "{", null, null, null)));

        mockMvc.perform(get("/internal/cancel-outbox/redrives/9")
                .header("X-User-Role", "ADMIN")
                .header("X-User-Id", "operator-1"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @ParameterizedTest
    @MethodSource("nonObjectStoredJson")
    void validNonObjectStoredAuditJsonFailsWithoutExposingItsValue(
        String storedJson,
        String sensitiveToken
    ) throws Exception {
        when(redriveRepository.findById(10L)).thenReturn(java.util.Optional.of(redrive(
            10L, 44L, CancelOutboxRedriveStatus.REDRIVING, null,
            "operator-1", "replay", Instant.parse("2026-08-11T00:00:00Z"),
            Instant.parse("2026-08-11T00:00:01Z"), null,
            storedJson, null, null, null)));

        mockMvc.perform(get("/internal/cancel-outbox/redrives/10")
                .header("X-User-Role", "ADMIN")
                .header("X-User-Id", "operator-1"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(content().string(not(containsString(sensitiveToken))));
    }

    @Test
    void missingRoleReturns401BeforeRedriveUseCaseInvocation() throws Exception {
        mockMvc.perform(post("/internal/cancel-outbox/41/redrives")
                .header("X-User-Id", "operator-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"retry\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INTERNAL_AUTHENTICATION_REQUIRED"));

        verifyNoInteractions(redriveRepository);
    }

    @Test
    void missingOperatorIdReturns403BeforeRedriveUseCaseInvocation() throws Exception {
        mockMvc.perform(post("/internal/cancel-outbox/41/redrives")
                .header("X-User-Role", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"retry\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("CANCEL_OUTBOX_REDRIVE_FORBIDDEN"));

        verifyNoInteractions(redriveRepository);
    }

    @Test
    void nonAdminRoleReturns403BeforeRedriveUseCaseInvocation() throws Exception {
        mockMvc.perform(post("/internal/cancel-outbox/41/redrives")
                .header("X-User-Role", "USER")
                .header("X-User-Id", "operator-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"retry\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("CANCEL_OUTBOX_REDRIVE_FORBIDDEN"));

        verifyNoInteractions(redriveRepository);
    }

    @ParameterizedTest
    @MethodSource("invalidReasons")
    void invalidReasonReturns400AndCreatesNoRedrive(String content) throws Exception {
        mockMvc.perform(post("/internal/cancel-outbox/41/redrives")
                .header("X-User-Role", "ADMIN")
                .header("X-User-Id", "operator-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(content))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(redriveRepository);
    }

    @ParameterizedTest
    @MethodSource("unreadableRequestBodies")
    void unreadableRequestBodyReturns400AndCreatesNoRedrive(String content) throws Exception {
        mockMvc.perform(post("/internal/cancel-outbox/41/redrives")
                .header("X-User-Role", "ADMIN")
                .header("X-User-Id", "operator-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(content))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(redriveRepository);
    }

    @Test
    void requestUseCaseExceptionsMapToStable404And409Codes() throws Exception {
        when(redriveRepository.createRequested(anyLong(), any(), any(), any()))
            .thenThrow(new CancelOutboxNotFoundException(99L), new ActiveRedriveExistsException(41L));

        mockMvc.perform(post("/internal/cancel-outbox/99/redrives")
                .header("X-User-Role", "ADMIN")
                .header("X-User-Id", "operator-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"retry\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("CANCEL_OUTBOX_NOT_FOUND"));

        mockMvc.perform(post("/internal/cancel-outbox/41/redrives")
                .header("X-User-Role", "ADMIN")
                .header("X-User-Id", "operator-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"retry\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ACTIVE_REDRIVE_EXISTS"));
    }

    @Test
    void missingRedriveMapsToStable404Code() throws Exception {
        when(redriveRepository.findById(404L)).thenReturn(java.util.Optional.empty());

        mockMvc.perform(get("/internal/cancel-outbox/redrives/404")
                .header("X-User-Role", "ADMIN")
                .header("X-User-Id", "operator-1"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("CANCEL_OUTBOX_REDRIVE_NOT_FOUND"));
    }

    @Test
    void adminOperatorCanInspectWithoutExposingReplayPayload() throws Exception {
        when(useCase.inspect(6L)).thenReturn(new CancelOutboxInspectionUseCase.Result(
            6L,
            27L,
            CancelOutboxDecision.REDRIVE_REQUIRED,
            null,
            leg(CancelRestoreLegStatus.APPLIED, 101L, "CANCELLED", null, null),
            leg(CancelRestoreLegStatus.NOT_APPLIED, 8L, "RESERVED", 1, 2)));

        mockMvc.perform(get("/internal/cancel-outbox/6")
                .header("X-User-Role", "ADMIN")
                .header("X-User-Id", "operator-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outboxId").value(6))
            .andExpect(jsonPath("$.cancelRequestId").value(27))
            .andExpect(jsonPath("$.decision").value("REDRIVE_REQUIRED"))
            .andExpect(jsonPath("$.reasonCode").doesNotExist())
            .andExpect(jsonPath("$.order.status").value("APPLIED"))
            .andExpect(jsonPath("$.order.evidence[0].targetId").value(101))
            .andExpect(jsonPath("$.stock.status").value("NOT_APPLIED"))
            .andExpect(jsonPath("$.stock.evidence[0].actualQuantity").value(1))
            .andExpect(jsonPath("$.stock.evidence[0].expectedQuantity").value(2))
            .andExpect(jsonPath("$.payload").doesNotExist())
            .andExpect(jsonPath("$.paymentKey").doesNotExist());

        verify(useCase).inspect(6L);
    }

    @Test
    void missingInternalAuthenticationReturns401BeforeInspection() throws Exception {
        mockMvc.perform(get("/internal/cancel-outbox/6")
                .header("X-User-Id", "operator-1"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INTERNAL_AUTHENTICATION_REQUIRED"));

        verify(useCase, never()).inspect(anyLong());
    }

    @Test
    void missingOperatorIdentityReturns403BeforeInspection() throws Exception {
        mockMvc.perform(get("/internal/cancel-outbox/6")
                .header("X-User-Role", "ADMIN"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("CANCEL_OUTBOX_REDRIVE_FORBIDDEN"));

        verify(useCase, never()).inspect(anyLong());
    }

    @Test
    void nonAdminOperatorReturns403BeforeInspection() throws Exception {
        mockMvc.perform(get("/internal/cancel-outbox/6")
                .header("X-User-Role", "USER")
                .header("X-User-Id", "operator-1"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("CANCEL_OUTBOX_REDRIVE_FORBIDDEN"));

        verify(useCase, never()).inspect(anyLong());
    }

    @Test
    void missingOutboxReturnsStable404() throws Exception {
        when(useCase.inspect(99L)).thenThrow(new CancelOutboxNotFoundException(99L));

        mockMvc.perform(get("/internal/cancel-outbox/99")
                .header("X-User-Role", "ADMIN")
                .header("X-User-Id", "operator-1"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("CANCEL_OUTBOX_NOT_FOUND"));
    }

    private static Stream<String> invalidReasons() {
        return Stream.of(
            "{\"reason\":null}",
            "{\"reason\":\" \\t\\n\"}",
            "{\"reason\":\"😀" + "😀".repeat(500) + "\"}");
    }

    private static Stream<String> unreadableRequestBodies() {
        return Stream.of("", "{\"reason\":", "null");
    }

    private static Stream<Arguments> nonObjectStoredJson() {
        return Stream.of(
            Arguments.of("\"sensitive-payment-key\"", "sensitive-payment-key"),
            Arguments.of("424242", "424242"),
            Arguments.of("null", "null"),
            Arguments.of("[\"sensitive-array-value\"]", "sensitive-array-value"));
    }

    private static CancelOutboxRedrive redrive(
        long id,
        long sourceOutboxId,
        CancelOutboxRedriveStatus status,
        CancelOutboxRedriveFailureStage failureStage,
        String requestedBy,
        String reason,
        Instant requestedAt,
        Instant startedAt,
        Instant completedAt,
        String result,
        String lastError,
        String beforeState,
        String afterState
    ) {
        return CancelOutboxRedrive.reconstitute(
            id, sourceOutboxId, status, failureStage, requestedBy, reason, requestedAt,
            startedAt, completedAt, result, lastError, beforeState, afterState);
    }

    private static CancelRestoreLegSnapshot leg(
        CancelRestoreLegStatus status,
        long targetId,
        String currentStatus,
        Integer actualQuantity,
        Integer expectedQuantity
    ) {
        return new CancelRestoreLegSnapshot(status, List.of(
            new CancelRestoreLegSnapshot.Evidence(
                targetId, currentStatus, actualQuantity, expectedQuantity)));
    }
}
