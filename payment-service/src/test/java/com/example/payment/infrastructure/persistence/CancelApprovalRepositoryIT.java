package com.example.payment.infrastructure.persistence;

import com.example.payment.application.interfaces.CancelApprovalRepository;
import com.example.payment.domain.entity.CancelApproval;
import com.example.payment.domain.entity.CancelApprovalStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("cancel_approval 영속 왕복/상태별 조회")
class CancelApprovalRepositoryIT extends AbstractRepositoryTest {

    @Autowired
    CancelApprovalJpaRepository jpa;

    CancelApprovalRepository repo;

    @BeforeEach
    void setUp() {
        repo = new CancelApprovalRepositoryImpl(jpa);
    }

    @Test
    @DisplayName("save 후 findById 로 왕복 — 모든 필드 보존")
    void save_and_find_roundtrip() {
        CancelApproval approval = CancelApproval.request(100L, "pay_key_1", 55L, "고객 변심");

        CancelApproval saved = repo.save(approval);

        assertThat(saved.getId()).isNotNull();
        Optional<CancelApproval> found = repo.findById(saved.getId());
        assertThat(found).isPresent();
        CancelApproval loaded = found.get();
        assertThat(loaded.getPaymentId()).isEqualTo(100L);
        assertThat(loaded.getPaymentKey()).isEqualTo("pay_key_1");
        assertThat(loaded.getRequesterUserId()).isEqualTo(55L);
        assertThat(loaded.getReason()).isEqualTo("고객 변심");
        assertThat(loaded.getStatus()).isEqualTo(CancelApprovalStatus.REQUESTED);
        assertThat(loaded.getDecidedByUserId()).isNull();
        assertThat(loaded.getDecidedRole()).isNull();
        assertThat(loaded.getDecisionReason()).isNull();
        assertThat(loaded.getCancelRequestId()).isNull();
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("REQUESTED 저장 → findActiveRequestedByPaymentId 조회, APPROVED 전이 후에는 조회되지 않음")
    void findActiveRequested_only_REQUESTED() {
        CancelApproval saved = repo.save(CancelApproval.request(200L, "pay_key_2", 1L, "단순 변심"));

        Optional<CancelApproval> active = repo.findActiveRequestedByPaymentId(200L);
        assertThat(active).isPresent();
        assertThat(active.get().getId()).isEqualTo(saved.getId());

        CancelApproval reloaded = repo.findById(saved.getId()).orElseThrow();
        reloaded.approve(9L, "ADMIN", 777L);
        repo.save(reloaded);

        assertThat(repo.findActiveRequestedByPaymentId(200L)).isEmpty();
    }

    @Test
    @DisplayName("findByStatus(REQUESTED) 는 REQUESTED 상태만 반환한다")
    void findByStatus_filters() {
        CancelApproval requested = repo.save(CancelApproval.request(300L, "pay_key_3", 2L, "사유1"));
        CancelApproval toReject = repo.save(CancelApproval.request(301L, "pay_key_4", 3L, "사유2"));
        CancelApproval reloaded = repo.findById(toReject.getId()).orElseThrow();
        reloaded.reject(9L, "ADMIN", "재고 부족");
        repo.save(reloaded);

        var requestedList = repo.findByStatus(CancelApprovalStatus.REQUESTED);
        assertThat(requestedList).extracting(CancelApproval::getId).containsExactly(requested.getId());

        var rejectedList = repo.findByStatus(CancelApprovalStatus.REJECTED);
        assertThat(rejectedList).extracting(CancelApproval::getId).containsExactly(toReject.getId());
    }
}
