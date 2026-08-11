package com.example.payment.domain.entity;

import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CancelOutboxRedriveTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-08-11T00:00:00Z");
    private static final Instant STARTED_AT = Instant.parse("2026-08-11T00:00:01Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-08-11T00:00:02Z");

    @Test
    void requestedPreservesAuditInputAndStartsWithoutExecutionFields() {
        var redrive = CancelOutboxRedrive.requested(
            41L, "operator-1", "  Kafka 장애 복구  ", REQUESTED_AT);

        assertThat(redrive.getSourceOutboxId()).isEqualTo(41L);
        assertThat(redrive.getStatus()).isEqualTo(CancelOutboxRedriveStatus.REQUESTED);
        assertThat(redrive.getRequestedBy()).isEqualTo("operator-1");
        assertThat(redrive.getReason()).isEqualTo("  Kafka 장애 복구  ");
        assertThat(redrive.getRequestedAt()).isEqualTo(REQUESTED_AT);
        assertThat(redrive.getFailureStage()).isNull();
        assertThat(redrive.getStartedAt()).isNull();
        assertThat(redrive.getCompletedAt()).isNull();
        assertThat(redrive.getResult()).isNull();
        assertThat(redrive.getLastError()).isNull();
        assertThat(redrive.getBeforeState()).isNull();
        assertThat(redrive.getAfterState()).isNull();
    }

    @Test
    void startMovesRequestedJobToRedriving() {
        var redrive = requested();

        redrive.start(STARTED_AT);

        assertThat(redrive.getStatus()).isEqualTo(CancelOutboxRedriveStatus.REDRIVING);
        assertThat(redrive.getStartedAt()).isEqualTo(STARTED_AT);
        assertThat(redrive.getFailureStage()).isNull();
    }

    @Test
    void terminalTransitionsRecordTheirTerminalStatusAndCompletionTime() {
        var resolved = started();
        resolved.resolve("{\"publish\":\"APPLIED\"}", "{\"order\":\"CANCELLED\"}", COMPLETED_AT);

        var alreadyApplied = started();
        alreadyApplied.resolveAlreadyApplied("{\"publish\":\"DUPLICATE\"}", "{\"order\":\"CANCELLED\"}", COMPLETED_AT);

        var rejected = started();
        rejected.reject("unsafe payload", "{\"order\":\"NOT_APPLIED\"}", "{\"order\":\"UNKNOWN\"}", COMPLETED_AT);

        assertThat(resolved.getStatus()).isEqualTo(CancelOutboxRedriveStatus.RESOLVED);
        assertThat(resolved.getCompletedAt()).isEqualTo(COMPLETED_AT);
        assertThat(alreadyApplied.getStatus()).isEqualTo(CancelOutboxRedriveStatus.RESOLVED_ALREADY_APPLIED);
        assertThat(alreadyApplied.getCompletedAt()).isEqualTo(COMPLETED_AT);
        assertThat(rejected.getStatus()).isEqualTo(CancelOutboxRedriveStatus.REJECTED);
        assertThat(rejected.getCompletedAt()).isEqualTo(COMPLETED_AT);
    }

    @Test
    void failedRequiresFailureStageAndTerminalStateCannotTransitionAgain() {
        var redrive = started();

        assertThatIllegalArgumentException().isThrownBy(() -> redrive.fail(
            null, "broker timeout", "{\"order\":\"NOT_APPLIED\"}", COMPLETED_AT));

        redrive.fail(CancelOutboxRedriveFailureStage.PUBLISH,
            "broker timeout", "{\"order\":\"NOT_APPLIED\"}", COMPLETED_AT);

        assertThat(redrive.getStatus()).isEqualTo(CancelOutboxRedriveStatus.FAILED);
        assertThat(redrive.getFailureStage()).isEqualTo(CancelOutboxRedriveFailureStage.PUBLISH);
        assertThat(redrive.getCompletedAt()).isEqualTo(COMPLETED_AT);
        assertThatThrownBy(() -> redrive.start(Instant.parse("2026-08-11T00:00:03Z")))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nonFailedTerminalTransitionsClearFailureStage() {
        var redrive = CancelOutboxRedrive.reconstitute(
            7L, 41L, CancelOutboxRedriveStatus.REDRIVING, CancelOutboxRedriveFailureStage.PUBLISH,
            "operator-1", "audit reason", REQUESTED_AT, STARTED_AT, null,
            null, "prior error", "{\"before\":true}", null);

        redrive.resolve("{\"publish\":\"APPLIED\"}", "{\"after\":true}", COMPLETED_AT);

        assertThat(redrive.getFailureStage()).isNull();
    }

    @Test
    void reconstituteRestoresEveryPersistedFieldWithoutNormalizingAuditText() {
        var redrive = CancelOutboxRedrive.reconstitute(
            7L, 41L, CancelOutboxRedriveStatus.FAILED, CancelOutboxRedriveFailureStage.CONVERGENCE,
            "operator-1", "  preserved audit reason  ", REQUESTED_AT, STARTED_AT, COMPLETED_AT,
            "{\"result\":\"x\"}", "safe error", "{\"before\":true}", "{\"after\":false}");

        assertThat(redrive.getId()).isEqualTo(7L);
        assertThat(redrive.getSourceOutboxId()).isEqualTo(41L);
        assertThat(redrive.getStatus()).isEqualTo(CancelOutboxRedriveStatus.FAILED);
        assertThat(redrive.getFailureStage()).isEqualTo(CancelOutboxRedriveFailureStage.CONVERGENCE);
        assertThat(redrive.getRequestedBy()).isEqualTo("operator-1");
        assertThat(redrive.getReason()).isEqualTo("  preserved audit reason  ");
        assertThat(redrive.getRequestedAt()).isEqualTo(REQUESTED_AT);
        assertThat(redrive.getStartedAt()).isEqualTo(STARTED_AT);
        assertThat(redrive.getCompletedAt()).isEqualTo(COMPLETED_AT);
        assertThat(redrive.getResult()).isEqualTo("{\"result\":\"x\"}");
        assertThat(redrive.getLastError()).isEqualTo("safe error");
        assertThat(redrive.getBeforeState()).isEqualTo("{\"before\":true}");
        assertThat(redrive.getAfterState()).isEqualTo("{\"after\":false}");
    }

    private CancelOutboxRedrive requested() {
        return CancelOutboxRedrive.requested(41L, "operator-1", "복구", REQUESTED_AT);
    }

    private CancelOutboxRedrive started() {
        var redrive = requested();
        redrive.start(STARTED_AT);
        return redrive;
    }
}
