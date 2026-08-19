# oncall 스킬 타깃 추상화 설계

- 날짜: 2026-07-13
- 상태: 설계 확정 (구현 계획 대기)
- 대상 스킬: `oncall-triage`, `oncall-pr`, `oncall-log`

## 1. 배경 / 문제

세 oncall 스킬(`oncall-triage`/`oncall-pr`/`oncall-log`)은 Grafana 알림이 Slack 채널로
발화되면 이를 진단하고, 선택된 대안으로 draft PR을 열고, 인시던트를 기록하는 파이프라인이다.

현재 이 스킬들은 특정 데모 프로젝트(`lsc713/incident-oncall`, spring-petclinic 기반)에
**하드코딩**되어 있다:

- **PR 대상 repo**: `oncall-pr`이 `lsc713/incident-oncall`로 고정.
- **진단 지표표**(`differential-table.md`): `petclinic_blocked_threads`,
  `petclinic_owner_search_queries` 등 petclinic 전용 커스텀 메트릭으로 작성.
- **메모리/설정 경로**: `memory-config.md`가 각 스킬 디렉터리에 **복제**되어 존재.

또한 이 스킬들은 정본이 `/Users/juho/Documents/oncall/.claude/skills/`에 있고,
각 프로젝트(예: docswithmulti)로 **복사되어** 배포된다(심링크 아님, 내용 동일).

### 요구

> 스킬을 다른 프로젝트에 **그대로 복사해 넣더라도**, 그 프로젝트에 맞게 진단할 수 있어야 한다.

즉 스킬 본문에서 프로젝트별로 달라지는 값을 제거하고, 프로젝트가 **스스로를 기술**하는
설정으로 분리해야 한다. docswithmulti가 첫 신규 타깃이고, 이후 임의의 프로젝트도
설정 파일만 추가하면 진단 대상이 될 수 있어야 한다.

## 2. 범위

### 이번 범위 (In Scope)
- 스킬 3종 본문의 하드코딩(repo·지표표·메모리 경로)을 설정 참조로 치환.
- 프로젝트 자기기술(self-describing) 설정 파일 구조 정의.
- 사용자 전역 설정(Slack 채널 + 메모리 경로) 분리.
- petclinic 하드코딩을 petclinic 자기기술 파일로 추출(정본 프로젝트가 계속 동작).
- docswithmulti 자기기술 파일 + docswithmulti용 `differential-table.md` 시드 신설.
- 각 스킬 dir의 `memory-config.md` 복제본 제거.

### 범위 밖 (Out of Scope, YAGNI)
- 알림 배선(Grafana → Slack) 신규 구축. **알림은 기존 공유 채널로 들어온다고 가정.**
- 관측 엔드포인트(Grafana/Loki/Prometheus)의 실제 연결·인증. 없으면 해당 쿼리 SKIP.
- 인시던트 원장(SQLite) 스키마 변경. 기존 `service` 컬럼으로 타깃 암묵 스코핑.
- 3번째 이후 실제 타깃 추가(구조만 지원, 실제 추가는 후속).
- 스킬의 안전 불변식 변경(draft-only·main 커밋 금지·라이브 DDL 금지·휴먼 게이트는 **불변**).

## 3. 결정 사항 (인터뷰 결과)

| # | 결정 | 선택 |
|---|------|------|
| 1 | 알림 소스 | 기존 공유 채널로 들어온다고 가정(배선은 범위 밖) |
| 2 | 타깃 결정 방식 | 알림 payload의 `service` 필드로 자동 해석 |
| 3 | petclinic 처리 | 하드코딩을 config로 추출, 모든 프로젝트 호환(진짜 멀티타깃) |
| 4 | 설정 위치 | 중앙 레지스트리 ❌, 사용자 전역(`~/.claude`) ❌ → **프로젝트별 단일 자기완결 파일** (이식성 요구 반영) |
| 5 | 메모리 위치 | **프로젝트 로컬** (완전 자기완결, 프로젝트 간 교차 검색 포기) |

## 4. 아키텍처

### 4.1 설정 — 단일 프로젝트 자기완결 (전역 없음)

모든 설정이 **프로젝트 안에** 있다. 전역(`~/.claude`) 설정은 없다. 스킬을 프로젝트에
복사해 넣으면 그 프로젝트가 외부 의존 0으로 동작한다(진짜 이식성).

| 위치 | 담는 값 |
|------|---------|
| `<프로젝트>/.claude/oncall-target.yml` | name · match.services · repo · source_path · branch_prefix · **slack_channel** · **memory{db,schema,vault,note_template}** · observability · differential_table. **프로젝트당 이 파일 하나가 전부를 기술.** |
| `<프로젝트>/.claude/oncall/differential-table.md` | 이 프로젝트의 감별 진단표 |
| `<프로젝트>/.claude/oncall/schema.sql` · `incident-note.template.md` | 인시던트 메모리 시드(원장 스키마 + 노트 템플릿). 프로젝트 로컬 |
| `<프로젝트>/.claude/oncall/incidents.db` · `vault/` | 런타임 생성(첫 oncall-log 실행 시 스키마로 부트스트랩). 프로젝트 로컬 |

**핵심 원칙**: 스킬 본문(SKILL.md)에는 프로젝트별 값이 하나도 없다. 로직만 남고,
값은 실행 프로젝트(cwd)의 `./.claude/oncall-target.yml` 하나에서 읽는다.

> **비밀 주의**: `slack_channel`은 채널 **식별자**(예 `C0…`)일 뿐 자격증명이 아니다.
> Slack 봇 토큰/인증은 MCP·환경변수에 남고 이 파일엔 절대 넣지 않는다.

### 4.2 프로젝트 기술자 스키마

메모리 경로는 **프로젝트 루트(`source_path`) 기준 상대경로**로 해석한다(프로젝트 로컬).

```yaml
# docswithmulti/.claude/oncall-target.yml
name: docswithmulti
match:                       # alert.service → 이 프로젝트 소속 판정
  services: [payment-service, order-service, merchant-limit-service,
             risk-management-service, product-service]
repo: lsc713/docswithmulti           # oncall-pr PR 대상
source_path: .                        # 스킬 실행 cwd(= 프로젝트 루트)
branch_prefix: fix                    # fix/<service>-<alertname>
slack_channel: <채널 id>              # 알림 채널 식별자(토큰 아님)
memory:                               # 프로젝트 로컬(루트 기준 상대경로)
  db: .claude/oncall/incidents.db
  schema: .claude/oncall/schema.sql
  vault: .claude/oncall/vault
  note_template: .claude/oncall/incident-note.template.md
observability:                        # 없거나 unreachable면 해당 쿼리 SKIP
  grafana: null
  loki: null
  prometheus: null
differential_table: .claude/oncall/differential-table.md
```

```yaml
# /Users/juho/Documents/oncall/.claude/oncall-target.yml  (petclinic 자기기술)
name: petclinic
match: { services: [petclinic] }
repo: lsc713/incident-oncall
source_path: .
branch_prefix: fix
slack_channel: <채널 id>              # 공유 채널이면 docswithmulti와 동일 값
memory:                               # 기존 위치 재사용(데이터 이전 불필요)
  db: memory/incidents.db
  schema: memory/schema.sql
  vault: vault
  note_template: memory/incident-note.template.md
observability: { grafana: http://localhost:3000, loki: http://localhost:3100, prometheus: http://localhost:3000 }
differential_table: .claude/oncall/differential-table.md
```

## 5. 동작 흐름 (호스트 프로젝트 스코프)

세 스킬 공통으로, 기존 LOCATE 앞에 **타깃 해석** 단계를 삽입한다.

1. **호스트 프로젝트 기술자 로드**: 실행 cwd의 `./.claude/oncall-target.yml` 하나를 읽어
   Slack 채널·메모리 경로·타깃값을 모두 얻는다. 없으면 "이 프로젝트에 oncall-target.yml
   없음 — 진단 대상 아님" 보고 후 graceful 종료.
2. **알림 필터링**: 공유 채널에서 알림을 읽되, `alert.service`가 **이 프로젝트의
   `match.services`에 드는 것만** 처리. 아닌 알림은 skip
   ("다른 프로젝트 알림 — 해당 프로젝트에서 스킬 실행").
3. 이후 모든 단계는 기술자 값을 사용:
   `$TARGET.repo` · `$TARGET.source_path` · `$TARGET.slack_channel` ·
   `$TARGET.memory.*` · `$TARGET.observability` · `$TARGET.differential_table`.
   메모리 상대경로는 프로젝트 루트 기준으로 해석한다.

결과: docswithmulti에서 스킬을 돌리면 docswithmulti 알림만, petclinic에 복사해 돌리면
petclinic 알림만 진단한다. 스킬 본문은 프로젝트를 몰라도 된다.

## 6. 스킬별 변경

| 스킬 | 현재 하드코딩 | 변경 후 |
|------|--------------|---------|
| `oncall-triage` | Loki/Prom 직접 · suspect 파일 repo · `differential-table.md` 로컬 | `$TARGET.observability`(null→SKIP) · `$TARGET.source_path` · `$TARGET.differential_table` |
| `oncall-pr` | "Demo repo `lsc713/incident-oncall` only" · 브랜치 | `repo = $TARGET.repo` · `$TARGET.branch_prefix/<service>-<alertname>` |
| `oncall-log` | `memory-config.md` 참조 | `$TARGET.memory.*` 참조 (프로젝트 로컬, 루트 기준 상대경로) |

### 안전 불변식 (변경 없음)
draft PR only · `main` 커밋 금지 · 라이브 DB DDL/DML 금지 · 휴먼 게이트(번호 선택) —
전부 그대로. 바뀌는 것은 "**어느** repo/지표냐"뿐, "**무엇을 해도 되냐**"는 불변.
`oncall-pr`의 "PR은 `$TARGET.repo`에만" 규칙은 여전히 화이트리스트로 작동한다
(기술자에 없는 repo로는 PR 불가).

## 7. differential-table 전략

- 현재 `oncall-triage/differential-table.md`(petclinic) →
  `/oncall/.claude/oncall/differential-table.md`로 **그대로 이동**.
- 신규 `docswithmulti/.claude/oncall/differential-table.md` **시드**:
  - **전이되는 제네릭 행**(Micrometer 표준, petclinic 아님):
    Hikari 커넥션풀 고갈 `hikaricp_connections_timeout_total>0`,
    스레드풀 포화 `tomcat_threads_busy`, GC 스래싱 `jvm_gc_pause_seconds_sum`.
  - **docswithmulti 고유 신호**: `payment_db` 병목(부하 실측에서 규명), 쿼리수
    (`LOADTEST_QUERYCOUNT_ENABLED`) 기반 N+1, k3s 수평 스케일아웃 포화.
    관측 배선 전까지는 confidence gap으로 명시.
  - **Family B(예외 → Class A 패치 경로)**는 원래부터 제네릭 → 그대로 상속.
  - 미매핑 알림은 표의 기존 **hybrid fallback**(같은 형태로 원인 열거 + 판별신호 명시 +
    confidence gap 표기)이 처리. 완벽한 표가 아니어도 동작한다.

## 8. 메모리 — 프로젝트 로컬 (스키마 변경 없음)

인시던트 원장(SQLite) + vault는 **프로젝트별로 로컬**이다. 각 프로젝트의
`memory.*` 경로(루트 기준 상대)가 자기 원장을 가리키고, 첫 `oncall-log` 실행 시
`schema.sql`로 부트스트랩된다. 완전 자기완결 — 스킬을 프로젝트에 복사해 넣으면 그
프로젝트 안에서 진단·기록이 닫힌다(외부 공유 원장 의존 없음).

- **트레이드오프**: 프로젝트 간 과거 인시던트 교차 검색은 되지 않는다(각 원장이 격리).
  `oncall-triage`의 past-incident 검색은 자기 프로젝트 원장 안에서만 스코핑된다.
- `incidents` 테이블 스키마는 그대로(기존 `service` 컬럼으로 프로젝트 내 서비스 구분).
  별도 `target` 컬럼/마이그레이션 불필요(YAGNI).
- petclinic은 기존 `memory/` 위치를 그대로 재사용(데이터 이전 불필요). docswithmulti는
  `.claude/oncall/`에 시드(schema.sql·incident-note.template.md 복사)하고 db·vault는 런타임 생성.

## 9. 변경 파일 요약

```
신설:
  /oncall/.claude/oncall-target.yml                           (petclinic 자기기술, 채널+메모리 포함)
  /oncall/.claude/oncall/differential-table.md                (기존 표 이동)
  /docswithmulti/.claude/oncall-target.yml                    (docswithmulti 자기기술, 채널+메모리 포함)
  /docswithmulti/.claude/oncall/differential-table.md         (신규 시드)
  /docswithmulti/.claude/oncall/schema.sql                    (메모리 시드 — /oncall/memory 에서 복사)
  /docswithmulti/.claude/oncall/incident-note.template.md     (메모리 시드 — 복사)

이동:
  oncall-triage/differential-table.md → /oncall/.claude/oncall/differential-table.md

삭제:
  각 스킬 dir의 memory-config.md 복제본 (oncall-triage, oncall-log)

수정(정본 = /oncall/.claude/skills/, 이후 docswithmulti 복사본 동기화):
  oncall-triage/SKILL.md   — 하드코딩 → ./.claude/oncall-target.yml 참조 + 타깃 해석 단계
  oncall-pr/SKILL.md       — repo → $TARGET.repo
  oncall-log/SKILL.md      — memory-config.md → $TARGET.memory.*

전역 설정 없음(~/.claude 미사용).
```

**정본 편집 원칙**: 스킬 본문 수정은 정본(`/oncall/.claude/skills/`)에서 하고, 완료 후
docswithmulti 복사본으로 동기화한다(내용 동일 유지). 기술자 파일은 프로젝트별로 생성한다.

## 10. 리스크 / 미해결

- **petclinic 실제 값(확인 완료)**: 서비스명 `petclinic`, Grafana `http://localhost:3000`,
  Loki `http://localhost:3100`. 지표: `petclinic_blocked_threads`/`_deadlocked_threads`/
  `_owner_search_queries`/`_cache_recompute_inflight`.
- **slack_channel 값**: 실제 채널 id 는 구현 시 `/oncall/.env` 등에서 확인해 각 기술자에
  채운다(식별자일 뿐 토큰 아님). 못 찾으면 `NEEDS_VALUE` 로 두고 보고.
- **docswithmulti 관측 배선**: `infra/load-test/observability/`에 Prometheus/Grafana가
  있으나 상시 가동은 아님. 배선 전에는 differential 판별 쿼리가 confidence gap으로 처리됨(정상 동작).
- **복사본 드리프트**: 정본↔복사본 동기화를 수동으로 하므로 향후 드리프트 가능.
  이번 범위에선 수동 동기화, 자동화는 후속(별도 논의).
