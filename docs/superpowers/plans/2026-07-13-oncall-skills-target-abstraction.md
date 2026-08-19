# oncall 스킬 타깃 추상화 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** oncall-triage/oncall-pr/oncall-log 세 스킬에서 petclinic/incident-oncall 하드코딩을 제거하고, 프로젝트가 스스로를 기술하는 **단일 자기완결 파일**(`.claude/oncall-target.yml`)만 갈아끼우면 임의 프로젝트를 진단할 수 있게 만든다.

**Architecture:** 전역 설정 없음. 모든 값이 프로젝트 안에 있다 — `<프로젝트>/.claude/oncall-target.yml` 하나가 name·match.services·repo·slack_channel·memory·observability·differential_table 전부를 기술하고, 동반 파일은 `<프로젝트>/.claude/oncall/`(differential-table.md, 메모리 시드 schema.sql·incident-note.template.md, 런타임 생성 incidents.db·vault)에 둔다. 메모리는 프로젝트 로컬(루트 기준 상대경로). 스킬은 실행 cwd 의 기술자를 읽어 그 프로젝트 서비스 알림만 진단한다. 스킬 본문 수정은 정본(`/Users/juho/Documents/oncall/.claude/skills/`)에 하고 docswithmulti 복사본으로 동기화한다.

**Tech Stack:** Markdown 스킬(SKILL.md) · YAML 설정 · Bash 검증(grep/파서). 자동 테스트 프레임워크가 없으므로 각 태스크의 "테스트"는 grep/YAML 파싱 assertion이다.

## Global Constraints

- **전역 설정 없음**: `~/.claude` 에 oncall 설정을 만들지 않는다. 모든 값은 프로젝트 안(`<프로젝트>/.claude/`).
- **정본 편집 위치**: `/Users/juho/Documents/oncall/.claude/skills/{oncall-triage,oncall-pr,oncall-log}/SKILL.md`. 완료 후 `/Users/juho/Documents/docswithmulti/.claude/skills/` 로 복사 동기화.
- **안전 불변식 불변**: draft PR only · `main` 커밋 금지 · 라이브 DB DDL/DML 금지 · 휴먼 게이트(번호 선택). 이 규칙 문구는 수정하지 않는다. 바뀌는 것은 "어느 repo/지표/채널/메모리냐"뿐.
- **기술자 참조 방식**: 스킬은 실행 cwd 의 `./.claude/oncall-target.yml` 하나를 읽어 `$TARGET.*` 로 참조. 메모리 상대경로는 프로젝트 루트 기준 해석.
- **비밀**: `slack_channel` 은 채널 식별자(예 `C0…`)일 뿐 자격증명 아님 — 허용. Slack 봇 토큰/인증은 MCP·환경변수에만, 파일에 금지.
- **petclinic 확정값**: 서비스 `petclinic` · Grafana `http://localhost:3000` · Loki `http://localhost:3100` · Prometheus `http://localhost:3000`(grafana proxy). 메모리는 기존 `memory/` 재사용.
- **git 추적 정책(중요)**: docswithmulti 는 `.gitignore` 로 `.claude/` 전체를 무시한다 → docswithmulti 의 oncall config·스킬 복사본은 **로컬 전용(디스크에만), git 커밋하지 않는다**. 정본 `/oncall` 만 커밋 대상(`.claude` 정상 추적). 따라서 브랜치는 `/oncall@refactor/oncall-target-abstraction` 하나만 사용. 커밋에 무관 변경(`application.yml` 등) 포함 금지.
- **Bash tmpdir**: 셸 호출 시 `export CLAUDE_CODE_TMPDIR=/Users/juho/.claude/tmp` 를 먼저 실행.
- **커밋 규칙**: `/oncall` 태스크만 해당 브랜치에 커밋. docswithmulti 태스크(Task 2·6)는 파일만 디스크에 배치하고 커밋 없음. 병합/PR 은 마지막에 사용자 결정.

---

### Task 1: petclinic 자기기술 파일 + differential-table 이동

**Files:**
- Create: `/Users/juho/Documents/oncall/.claude/oncall-target.yml`
- Move: `/Users/juho/Documents/oncall/.claude/skills/oncall-triage/differential-table.md` → `/Users/juho/Documents/oncall/.claude/oncall/differential-table.md`
- Read(채널값 출처): `/Users/juho/Documents/oncall/.env`

**Interfaces:**
- Produces: petclinic 자기완결 기술자. 키 스키마 `name/match.services/repo/source_path/branch_prefix/slack_channel/memory{db,schema,vault,note_template}/observability{grafana,loki,prometheus}/differential_table` — Task 2~5 가 참조하는 `$TARGET` 의 레퍼런스 구현.

- [ ] **Step 1: 실제 Slack 채널 값 확인**

Run:
```bash
export CLAUDE_CODE_TMPDIR=/Users/juho/.claude/tmp
grep -rIh "ONCALL_SLACK_CHANNEL" /Users/juho/Documents/oncall/.env /Users/juho/Documents/oncall/docker-compose.yml 2>/dev/null | head
```
Expected: `ONCALL_SLACK_CHANNEL=C0XXXX` 형태 실제 채널 id 확인. 리터럴이 없고 `${...}` 참조뿐이면 `/oncall/.env` 실값 사용. 어디에도 없으면 값에 `NEEDS_VALUE` 를 넣고 concern 으로 보고(날조 금지).

- [ ] **Step 2: petclinic 기술자 작성**

Write `/Users/juho/Documents/oncall/.claude/oncall-target.yml` (Step 1 채널 id 로 `<채널 id>` 치환):
```yaml
# petclinic (incident-oncall 데모) 자기기술 — 자기완결
name: petclinic
match:
  services: [petclinic]          # alert.service 가 이 목록에 들면 이 프로젝트 소속
repo: lsc713/incident-oncall      # oncall-pr PR 대상 (화이트리스트)
source_path: .                    # 스킬 실행 cwd(= 이 프로젝트 루트)
branch_prefix: fix                # fix/<service>-<alertname>
slack_channel: <채널 id>          # 알림 채널 식별자(토큰 아님)
memory:                           # 프로젝트 로컬(루트 기준 상대). 기존 위치 재사용
  db: memory/incidents.db
  schema: memory/schema.sql
  vault: vault
  note_template: memory/incident-note.template.md
observability:                    # 없거나 unreachable 이면 해당 쿼리 SKIP
  grafana: http://localhost:3000
  loki: http://localhost:3100
  prometheus: http://localhost:3000   # grafana 데이터소스 프록시 경유
differential_table: .claude/oncall/differential-table.md
```

- [ ] **Step 3: differential-table 이동(git mv)**

Run:
```bash
export CLAUDE_CODE_TMPDIR=/Users/juho/.claude/tmp
cd /Users/juho/Documents/oncall
mkdir -p .claude/oncall
git mv .claude/skills/oncall-triage/differential-table.md .claude/oncall/differential-table.md
```
Expected: 에러 없이 이동. (미추적이면 `git mv` 실패 시 `mv` 로 대체.)

- [ ] **Step 4: 검증 — 이동 완료 + 기술자 파싱(전 키)**

Run:
```bash
export CLAUDE_CODE_TMPDIR=/Users/juho/.claude/tmp
[ -f /Users/juho/Documents/oncall/.claude/oncall/differential-table.md ] && echo "OK moved" || echo "FAIL moved"
[ ! -f /Users/juho/Documents/oncall/.claude/skills/oncall-triage/differential-table.md ] && echo "OK old gone" || echo "FAIL old remains"
python3 -c "import yaml; d=yaml.safe_load(open('/Users/juho/Documents/oncall/.claude/oncall-target.yml')); req=['name','match','repo','source_path','branch_prefix','slack_channel','memory','observability','differential_table']; miss=[k for k in req if k not in d]; assert not miss, miss; assert d['name']=='petclinic'; assert 'petclinic' in d['match']['services']; assert d['repo']=='lsc713/incident-oncall'; assert all(k in d['memory'] for k in ['db','schema','vault','note_template']); print('OK yaml', d['name'])"
```
Expected: `OK moved` / `OK old gone` / `OK yaml petclinic`.

- [ ] **Step 5: 커밋**

```bash
cd /Users/juho/Documents/oncall
git add .claude/oncall-target.yml .claude/oncall/differential-table.md
git commit -m "refactor(oncall): petclinic 하드코딩을 자기완결 기술자로 추출 + differential-table 이동"
```

---

### Task 2: docswithmulti 자기기술 파일 + differential-table 시드 + 메모리 시드

**Files:**
- Create: `/Users/juho/Documents/docswithmulti/.claude/oncall-target.yml`
- Create: `/Users/juho/Documents/docswithmulti/.claude/oncall/differential-table.md`
- Copy: `/Users/juho/Documents/oncall/.claude/oncall/differential-table.md` 는 복사 대상 아님(docswithmulti 는 자체 시드). 메모리 시드만 복사:
  - `/Users/juho/Documents/oncall/memory/schema.sql` → `/Users/juho/Documents/docswithmulti/.claude/oncall/schema.sql`
  - `/Users/juho/Documents/oncall/memory/incident-note.template.md` → `/Users/juho/Documents/docswithmulti/.claude/oncall/incident-note.template.md`

**Interfaces:**
- Consumes: Task 1 이 정의한 기술자 스키마(전 키).
- Produces: docswithmulti 자기완결 타깃. 스킬을 docswithmulti 에서 실행하면 이 파일이 진단 대상·채널·메모리를 결정한다.

- [ ] **Step 1: docswithmulti 기술자 작성**

Write `/Users/juho/Documents/docswithmulti/.claude/oncall-target.yml` (`<채널 id>` 는 Task 1 Step 1 과 동일 값 — 공유 채널):
```yaml
# docswithmulti (결제 취소 시스템) 자기기술 — 자기완결
name: docswithmulti
match:
  services: [payment-service, order-service, merchant-limit-service,
             risk-management-service, product-service]
repo: lsc713/docswithmulti
source_path: .
branch_prefix: fix
slack_channel: <채널 id>          # 공유 알림 채널(petclinic 과 동일 값)
memory:                           # 프로젝트 로컬 — db·vault 는 첫 실행 시 생성
  db: .claude/oncall/incidents.db
  schema: .claude/oncall/schema.sql
  vault: .claude/oncall/vault
  note_template: .claude/oncall/incident-note.template.md
observability:                    # 상시 가동 아님 — 배선 전엔 confidence gap 으로 SKIP
  grafana: null
  loki: null
  prometheus: null
differential_table: .claude/oncall/differential-table.md
```

- [ ] **Step 2: 메모리 시드 복사(schema + note template)**

Run:
```bash
export CLAUDE_CODE_TMPDIR=/Users/juho/.claude/tmp
mkdir -p /Users/juho/Documents/docswithmulti/.claude/oncall
cp /Users/juho/Documents/oncall/memory/schema.sql /Users/juho/Documents/docswithmulti/.claude/oncall/schema.sql
cp /Users/juho/Documents/oncall/memory/incident-note.template.md /Users/juho/Documents/docswithmulti/.claude/oncall/incident-note.template.md
```
Expected: 두 파일 복사됨.

- [ ] **Step 3: docswithmulti differential-table 시드 작성**

Write `/Users/juho/Documents/docswithmulti/.claude/oncall/differential-table.md`:
```markdown
# Differential-Diagnosis Table (docswithmulti)

How to use: given an alert's symptom **family**, enumerate the candidate causes
below, then query each candidate's **discriminating signal**. The cause whose
signal uniquely fires is the confirmed cause. If several fire or none do, report
the ambiguity — **demote, don't fabricate**.

**관측 배선 주의:** docswithmulti 의 Prometheus/Grafana 는 `infra/load-test/observability/`
에 있으나 상시 가동이 아니다. 엔드포인트가 unreachable 이면 해당 판별 신호는
**confidence gap** 으로 표기하고 차단하지 않는다(hybrid fallback).

**Hybrid fallback:** 표에 없는 증상/신호는 같은 형태로 추론한다 — 후보 원인 열거,
각 후보를 가르는 신호 명명, 도달 가능하면 쿼리, 아니면 confidence gap 명시.

## Family A — slow / high-latency response (LatencySaturation 우산)
"느림"에서 멈추지 말고 팬아웃한다:

| Candidate cause | Discriminating signal (query this) | Class |
|---|---|---|
| Connection-pool exhaustion (Hikari) | `hikaricp_connections_timeout_total > 0` (+ pending 높음, active == max) | C |
| Thread-pool saturation | `tomcat_threads_busy` 가 max / 포화 워커가 RUNNABLE/BLOCKED | C |
| payment_db 병목 | payment_db 대상 쿼리 지연/대기 상승, 타 DB 정상 (부하 실측에서 규명된 지배 병목) | C |
| N+1 queries | 요청당 쿼리수 상승 (`LOADTEST_QUERYCOUNT_ENABLED` 계측) | C |
| GC thrashing | `rate(jvm_gc_pause_seconds_sum)` 오버헤드 높음 | C |
| k3s 수평 스케일아웃 포화 | replica 증가에도 처리량 정체 / 특정 파드 편중 | C |
| Query-plan regression | latency 높으나 요청당 쿼리수 == 1 (한 방 느린 Seq Scan) | C |

## Family B — errors / 5xx (예외 행만 Class A 패치 경로, 나머지 Class C)
| Candidate cause | Discriminating signal | Class |
|---|---|---|
| Synchronous app exception | Loki 에 실제 앱 스택트레이스 → 루트 `file:line` | A (patch path) |
| Wrapped / framework-only 5xx | 에러율 상승하나 쓸 만한 앱 프레임 없음 | C |
| Silent corruption | HTTP 200 + 잘못된 결과, 예외 없음 (정답은 spec 에 있음) | C |
| Infra DB-down | 루트 원인이 `ConnectException` (네트워크/DB), 앱 결함 아님 | C |

## Family C — crash / resource / scheduling (infra)
| Candidate cause | Discriminating signal | Class |
|---|---|---|
| OOM-kill | k8s 이벤트 `OOMKilled` / 파드 재시작 *(미배선 → confidence gap)* | C |
| Pod evicted / FailedScheduling | k8s 이벤트 `Evicted` / `FailedScheduling` *(미배선 → confidence gap)* | C |

## Alertname → family quick map
LatencySaturation, ConnectionPoolExhausted, ThreadPoolSaturation, PaymentDbBottleneck,
NPlusOneQueries, GcThrashing, ScaleoutSaturation, QueryPlanRegression → **Family A**.
앱 예외 알림 → **Family B (patch path)**. ErrorRatioNoTrace, SilentCorruption, InfraDbDown → **Family B**.
KubeOOMKilled, KubePodEvicted, KubeFailedScheduling → **Family C**.
```

- [ ] **Step 4: 검증 — YAML 전 키 + 시드 파일 + petclinic 오염 없음**

Run:
```bash
export CLAUDE_CODE_TMPDIR=/Users/juho/.claude/tmp
python3 -c "import yaml; d=yaml.safe_load(open('/Users/juho/Documents/docswithmulti/.claude/oncall-target.yml')); req=['name','match','repo','source_path','branch_prefix','slack_channel','memory','observability','differential_table']; miss=[k for k in req if k not in d]; assert not miss, miss; assert d['name']=='docswithmulti'; assert 'payment-service' in d['match']['services']; assert d['repo']=='lsc713/docswithmulti'; assert d['memory']['db']=='.claude/oncall/incidents.db'; print('OK yaml', d['name'])"
for f in differential-table.md schema.sql incident-note.template.md; do [ -f "/Users/juho/Documents/docswithmulti/.claude/oncall/$f" ] && echo "OK $f" || echo "FAIL $f"; done
grep -q "petclinic_" /Users/juho/Documents/docswithmulti/.claude/oncall/differential-table.md && echo "FAIL: petclinic 오염" || echo "OK: petclinic 없음"
```
Expected: `OK yaml docswithmulti` / `OK differential-table.md` / `OK schema.sql` / `OK incident-note.template.md` / `OK: petclinic 없음`.

- [ ] **Step 5: 커밋 없음(로컬 전용)**

docswithmulti 는 `.claude/` 를 gitignore 하므로 이 태스크의 파일은 디스크에만 두고 **커밋하지 않는다**. Step 4 검증 통과로 완료.

---

### Task 3: oncall-triage SKILL.md 정본 수정

**Files:**
- Modify: `/Users/juho/Documents/oncall/.claude/skills/oncall-triage/SKILL.md`
- Delete: `/Users/juho/Documents/oncall/.claude/skills/oncall-triage/memory-config.md`

**Interfaces:**
- Consumes: Task 1/2 기술자(`./.claude/oncall-target.yml` — `$TARGET.slack_channel/memory/repo/source_path/observability/differential_table/match.services`).
- Produces: 타깃-무관 oncall-triage 본문. 단일 기술자에서 채널·메모리·타깃값을 읽는 규약.

- [ ] **Step 1: RESOLVE TARGET 단계 삽입**

`### 1. DISCOVER — enumerate available tools` 바로 앞에 새 절 추가:
```markdown
### 0. RESOLVE TARGET — 호스트 프로젝트 기술자 로드
스킬은 **실행되는 프로젝트(cwd)** 를 진단한다. 전역 설정은 없다.
1. 기술자 로드: `./.claude/oncall-target.yml` 하나에서 `slack_channel`·`memory.*`·`repo`·`source_path`·`observability`·`differential_table`·`match.services` 를 읽는다. 없으면 "이 프로젝트에 oncall-target.yml 없음 — 진단 대상 아님" 보고 후 graceful 종료.
2. 이후 단계는 기술자 값을 `$TARGET.repo` / `$TARGET.source_path` / `$TARGET.slack_channel` / `$TARGET.memory.*` / `$TARGET.observability` / `$TARGET.differential_table` 로 참조한다(본문에 프로젝트별 값을 두지 않는다). 메모리 상대경로는 프로젝트 루트 기준으로 해석.
3. **알림 필터링**(LOCATE 에서): `alert.service` 가 `$TARGET.match.services` 에 드는 알림만 처리. 아닌 알림은 skip("다른 프로젝트 알림 — 해당 프로젝트에서 스킬 실행").
```

- [ ] **Step 2: 채널·메모리 참조 치환**

Edit — 다음 문자열 치환:
- `Channel id is` 문장의 `` `ONCALL_SLACK_CHANNEL` in `.env`. `` → `` `$TARGET.slack_channel` (from `./.claude/oncall-target.yml`). ``
- `` `memory-config.md`의 `MEMORY_DB` 사용 `` → `` `$TARGET.memory.db`(프로젝트 로컬, 루트 기준 상대) 사용 ``

- [ ] **Step 3: differential-table 참조를 $TARGET 으로 치환(replace_all)**

Edit(replace_all): 본문의 리터럴 `` `differential-table.md` `` 를 전부 `` `$TARGET.differential_table` `` 로 치환.

- [ ] **Step 4: LOCATE 알림 필터링 문구 추가**

`### 2. LOCATE` 의 큐잉 규칙(2번 항목) 뒤에 한 줄 추가:
```markdown
   - **타깃 필터:** `alert.service` 가 `$TARGET.match.services` 에 없는 알림은 이 프로젝트 소속이 아니므로 skip(리액션 없이) — 해당 프로젝트에서 스킬을 실행해야 한다.
```

- [ ] **Step 5: COLLECT/ANALYZE 소스 참조를 $TARGET 으로**

Edit — 다음 치환:
- `Read the suspect file in the repo to confirm` → ``Read the suspect file in `$TARGET.source_path` to confirm``
- COLLECT 우선순위 줄의 `GitHub (recent commits/PRs on the affected service)` 뒤에 ``(repo = `$TARGET.repo`, 소스 = `$TARGET.source_path`)`` 를 덧붙인다.
- Loki/Prometheus 수집 문단에 ``엔드포인트는 `$TARGET.observability` 에서 읽고, null/unreachable 이면 SKIP(confidence gap)`` 한 줄 추가.

- [ ] **Step 6: petclinic_* 예시 제네릭화(2곳)**

Edit — 다음 치환:
- `` (e.g. `petclinic_blocked_threads` high **and** `petclinic_deadlocked_threads = 0` → lock contention, NOT a `` → `` (예: `$TARGET.differential_table` 의 판별지표↑ **and** 배타지표=0 → lock contention, NOT a ``
- `` *"확정: 락 경합 (petclinic_blocked_threads↑, petclinic_deadlocked_threads=0)"*. `` → `` *"확정: 락 경합 (<판별지표>↑, <배타지표>=0)"*. ``

- [ ] **Step 7: memory-config.md 복제본 삭제**

Run:
```bash
export CLAUDE_CODE_TMPDIR=/Users/juho/.claude/tmp
cd /Users/juho/Documents/oncall
git rm .claude/skills/oncall-triage/memory-config.md 2>/dev/null || rm -f .claude/skills/oncall-triage/memory-config.md
```

- [ ] **Step 8: 검증 — 하드코딩 미잔존**

Run:
```bash
export CLAUDE_CODE_TMPDIR=/Users/juho/.claude/tmp
f=/Users/juho/Documents/oncall/.claude/skills/oncall-triage/SKILL.md
grep -n "petclinic_\|memory-config.md\|in \`.env\`" "$f" && echo "FAIL: 하드코딩 잔존(위 라인)" || echo "OK: 하드코딩 없음"
grep -q "RESOLVE TARGET" "$f" && echo "OK: RESOLVE TARGET 있음" || echo "FAIL: RESOLVE TARGET 없음"
grep -q '\$TARGET.differential_table' "$f" && echo "OK: \$TARGET.differential_table" || echo "FAIL"
grep -q '\$TARGET.slack_channel' "$f" && echo "OK: \$TARGET.slack_channel" || echo "FAIL"
[ ! -f "$(dirname $f)/memory-config.md" ] && echo "OK: memory-config 삭제" || echo "FAIL: memory-config 잔존"
```
Expected: `OK: 하드코딩 없음` / `OK: RESOLVE TARGET 있음` / `OK: $TARGET.differential_table` / `OK: $TARGET.slack_channel` / `OK: memory-config 삭제`.

- [ ] **Step 9: 커밋**

```bash
cd /Users/juho/Documents/oncall
git add .claude/skills/oncall-triage/SKILL.md
git commit -m "refactor(oncall-triage): 타깃 하드코딩 제거 → 단일 기술자(\$TARGET) 참조"
```

---

### Task 4: oncall-pr SKILL.md 정본 수정

**Files:**
- Modify: `/Users/juho/Documents/oncall/.claude/skills/oncall-pr/SKILL.md`

**Interfaces:**
- Consumes: Task 1/2 기술자(`$TARGET.repo`, `$TARGET.branch_prefix`, `$TARGET.source_path`).
- Produces: PR 대상이 `$TARGET.repo` 화이트리스트로 동작하는 oncall-pr.

- [ ] **Step 1: 안전선의 데모 repo 고정을 화이트리스트로 치환**

Edit — 다음 치환:
- `` Demo repo **`lsc713/incident-oncall`** only. `` → `` PR repo = 호스트 프로젝트의 `$TARGET.repo` **only** (화이트리스트 — 기술자에 없는 repo 로는 PR 금지). ``

- [ ] **Step 2: RESOLVE TARGET 참조 추가(DISCOVER)**

`### 1. DISCOVER` 첫 줄 앞에 추가:
```markdown
**타깃 해석:** 실행 프로젝트(cwd)의 `./.claude/oncall-target.yml` 를 로드해 `$TARGET.repo` / `$TARGET.branch_prefix` / `$TARGET.source_path` 를 얻는다. 없으면 "이 프로젝트에 oncall-target.yml 없음" 보고 후 종료. (전역 설정 없음 — 채널·메모리도 이 기술자에서.)
```

- [ ] **Step 3: BUILD 의 repo/branch 를 $TARGET 으로**

Edit — 다음 치환:
- `` In `lsc713/incident-oncall`, new branch `fix/<service>-<alertname>`: `` → ``In `$TARGET.repo`, new branch `$TARGET.branch_prefix/<service>-<alertname>` (소스는 `$TARGET.source_path`):``

- [ ] **Step 4: Red Flag 의 repo 고정 치환**

Edit — 다음 치환:
- `` a PR outside `lsc713/incident-oncall` → wrong. `` → `` a PR outside `$TARGET.repo` → wrong. ``

- [ ] **Step 5: 검증**

Run:
```bash
export CLAUDE_CODE_TMPDIR=/Users/juho/.claude/tmp
f=/Users/juho/Documents/oncall/.claude/skills/oncall-pr/SKILL.md
grep -n "incident-oncall" "$f" && echo "FAIL: repo 하드코딩 잔존" || echo "OK: repo 하드코딩 없음"
grep -q '\$TARGET.repo' "$f" && echo "OK: \$TARGET.repo 참조" || echo "FAIL"
grep -q "draft" "$f" && echo "OK: draft 불변식 유지" || echo "FAIL: draft 문구 소실"
```
Expected: `OK: repo 하드코딩 없음` / `OK: $TARGET.repo 참조` / `OK: draft 불변식 유지`.

- [ ] **Step 6: 커밋**

```bash
cd /Users/juho/Documents/oncall
git add .claude/skills/oncall-pr/SKILL.md
git commit -m "refactor(oncall-pr): PR 대상 repo 를 \$TARGET.repo 화이트리스트로"
```

---

### Task 5: oncall-log SKILL.md 정본 수정 + memory-config 삭제

**Files:**
- Modify: `/Users/juho/Documents/oncall/.claude/skills/oncall-log/SKILL.md`
- Delete: `/Users/juho/Documents/oncall/.claude/skills/oncall-log/memory-config.md`

**Interfaces:**
- Consumes: Task 1/2 기술자(`$TARGET.memory.{db,schema,vault,note_template}`).
- Produces: 기술자의 프로젝트-로컬 메모리 경로를 참조하는 oncall-log.

- [ ] **Step 1: memory-config.md 참조를 기술자 memory 블록으로 치환**

Edit — 다음 치환:
- `` `memory-config.md`에서 `MEMORY_DB`/`MEMORY_SCHEMA`/`VAULT_DIR`/`NOTE_TEMPLATE`(절대경로)를 읽는다. `` → `` `./.claude/oncall-target.yml`의 `$TARGET.memory.{db,schema,vault,note_template}`(프로젝트 루트 기준 상대경로)를 읽는다. ``

- [ ] **Step 2: RESOLVE TARGET 참조 추가(DISCOVER)**

`### 1. DISCOVER` 절 안, 메모리 접근을 설명하는 문장 근처에 한 줄 추가(적절한 위치):
```markdown
전역 설정은 없다 — 실행 프로젝트(cwd)의 `./.claude/oncall-target.yml` 하나에서 메모리 경로(`$TARGET.memory.*`)와 채널을 읽는다. 없으면 "이 프로젝트에 oncall-target.yml 없음" 보고 후 종료.
```

- [ ] **Step 3: memory-config.md 복제본 삭제**

Run:
```bash
export CLAUDE_CODE_TMPDIR=/Users/juho/.claude/tmp
cd /Users/juho/Documents/oncall
git rm .claude/skills/oncall-log/memory-config.md 2>/dev/null || rm -f .claude/skills/oncall-log/memory-config.md
```

- [ ] **Step 4: 검증**

Run:
```bash
export CLAUDE_CODE_TMPDIR=/Users/juho/.claude/tmp
f=/Users/juho/Documents/oncall/.claude/skills/oncall-log/SKILL.md
grep -n "memory-config.md" "$f" && echo "FAIL: memory-config 참조 잔존" || echo "OK: 참조 없음"
grep -q 'oncall-target.yml' "$f" && echo "OK: 기술자 참조" || echo "FAIL"
grep -q '\$TARGET.memory' "$f" && echo "OK: \$TARGET.memory 참조" || echo "FAIL"
[ ! -f "$(dirname $f)/memory-config.md" ] && echo "OK: 복제본 삭제" || echo "FAIL: 복제본 잔존"
```
Expected: `OK: 참조 없음` / `OK: 기술자 참조` / `OK: $TARGET.memory 참조` / `OK: 복제본 삭제`.

- [ ] **Step 5: 커밋**

```bash
cd /Users/juho/Documents/oncall
git add .claude/skills/oncall-log/SKILL.md
git commit -m "refactor(oncall-log): memory-config 복제 제거 → \$TARGET.memory(프로젝트 로컬) 참조"
```

---

### Task 6: 정본 → docswithmulti 복사본 동기화

**Files:**
- Overwrite: `/Users/juho/Documents/docswithmulti/.claude/skills/{oncall-triage,oncall-pr,oncall-log}/SKILL.md`
- Delete: docswithmulti 복사본의 `oncall-triage/memory-config.md`, `oncall-log/memory-config.md`, `oncall-triage/differential-table.md`(petclinic 표 — docswithmulti 는 자체 시드 사용)

**Interfaces:**
- Consumes: Task 3/4/5 정본 SKILL.md.
- Produces: docswithmulti 복사본이 정본과 동일 본문 + 자체 기술자(Task 2) 사용.

- [ ] **Step 1: 3개 SKILL.md 동기화 + 복제본 삭제**

Run:
```bash
export CLAUDE_CODE_TMPDIR=/Users/juho/.claude/tmp
SRC=/Users/juho/Documents/oncall/.claude/skills
DST=/Users/juho/Documents/docswithmulti/.claude/skills
for s in oncall-triage oncall-pr oncall-log; do cp "$SRC/$s/SKILL.md" "$DST/$s/SKILL.md"; done
rm -f "$DST/oncall-triage/memory-config.md" "$DST/oncall-log/memory-config.md" "$DST/oncall-triage/differential-table.md"
```

- [ ] **Step 2: 검증 — 본문 동일 + 복제본 삭제 + 자체 기술자 존재**

Run:
```bash
export CLAUDE_CODE_TMPDIR=/Users/juho/.claude/tmp
SRC=/Users/juho/Documents/oncall/.claude/skills
DST=/Users/juho/Documents/docswithmulti/.claude/skills
for s in oncall-triage oncall-pr oncall-log; do diff -q "$SRC/$s/SKILL.md" "$DST/$s/SKILL.md" >/dev/null && echo "OK sync $s" || echo "FAIL sync $s"; done
[ ! -f "$DST/oncall-triage/memory-config.md" ] && [ ! -f "$DST/oncall-log/memory-config.md" ] && [ ! -f "$DST/oncall-triage/differential-table.md" ] && echo "OK: 복제본 삭제" || echo "FAIL: 복제본 잔존"
[ -f /Users/juho/Documents/docswithmulti/.claude/oncall-target.yml ] && echo "OK: 자체 기술자" || echo "FAIL"
```
Expected: `OK sync` ×3 / `OK: 복제본 삭제` / `OK: 자체 기술자`.

- [ ] **Step 3: 커밋 없음(로컬 전용)**

docswithmulti `.claude/` 는 gitignore 대상 → 동기화된 SKILL.md·삭제는 디스크에만 반영하고 **커밋하지 않는다**. Step 2 검증 통과로 완료.

---

### Task 7: 전체 구조 검증 (dry-run)

**Files:** (읽기 전용 검증)

- [ ] **Step 1: 두 프로젝트 전 스킬 하드코딩 스윕**

Run:
```bash
export CLAUDE_CODE_TMPDIR=/Users/juho/.claude/tmp
for base in /Users/juho/Documents/oncall /Users/juho/Documents/docswithmulti; do
  echo "=== $base ==="
  grep -rn "incident-oncall\|petclinic_\|memory-config.md" "$base/.claude/skills/oncall-triage/SKILL.md" "$base/.claude/skills/oncall-pr/SKILL.md" "$base/.claude/skills/oncall-log/SKILL.md" && echo "FAIL: 하드코딩 잔존" || echo "OK: 스킬 본문 청정"
done
```
Expected: 두 base 모두 `OK: 스킬 본문 청정`. (참고: `oncall-autopilot` 은 deprecated 라 범위 밖.)

- [ ] **Step 2: 기술자 YAML 전수 파싱(전 키 + memory 하위)**

Run:
```bash
export CLAUDE_CODE_TMPDIR=/Users/juho/.claude/tmp
for y in /Users/juho/Documents/oncall/.claude/oncall-target.yml /Users/juho/Documents/docswithmulti/.claude/oncall-target.yml; do
  python3 -c "import yaml,sys; d=yaml.safe_load(open('$y')); req=['name','match','repo','source_path','branch_prefix','slack_channel','memory','observability','differential_table']; miss=[k for k in req if k not in d]; mm=[k for k in ['db','schema','vault','note_template'] if k not in d.get('memory',{})]; sys.exit('FAIL '+'$y'+' missing '+str(miss+mm)) if (miss or mm) else print('OK', d['name'])"
done
```
Expected: `OK petclinic` / `OK docswithmulti`.

- [ ] **Step 3: differential-table 참조 무결성**

Run:
```bash
export CLAUDE_CODE_TMPDIR=/Users/juho/.claude/tmp
for base in /Users/juho/Documents/oncall /Users/juho/Documents/docswithmulti; do
  rel=$(python3 -c "import yaml; print(yaml.safe_load(open('$base/.claude/oncall-target.yml'))['differential_table'])")
  [ -f "$base/$rel" ] && echo "OK $base/$rel" || echo "FAIL: $base/$rel 없음"
done
```
Expected: 두 경로 모두 `OK`.

- [ ] **Step 4: 메모리 시드 경로 실재(docswithmulti)**

Run:
```bash
export CLAUDE_CODE_TMPDIR=/Users/juho/.claude/tmp
b=/Users/juho/Documents/docswithmulti
python3 -c "import yaml; d=yaml.safe_load(open('$b/.claude/oncall-target.yml'))['memory']; print(d['schema'], d['note_template'])" | tr ' ' '\n' | while read p; do [ -f "$b/$p" ] && echo "OK $p" || echo "FAIL $p"; done
```
Expected: `OK .claude/oncall/schema.sql` / `OK .claude/oncall/incident-note.template.md`. (db·vault 는 런타임 생성이라 없어도 정상.)

- [ ] **Step 5: 최종 리뷰 요청**

두 repo 변경을 요약해 사용자에게 보고. docswithmulti `feat/oncall-target-docswithmulti`, oncall `refactor/oncall-target-abstraction` 브랜치 상태 확인 후 병합/PR 여부를 사용자와 결정.

---

## Self-Review (작성자 체크)

**Spec coverage:** 설계 §4.1(단일 자기완결)→Task1+2, §4.2(스키마 전 키)→Task1/2, §5(동작 흐름)→Task3 Step1/4, §6(스킬별 변경)→Task3/4/5, §7(differential 전략)→Task1 Step3·Task2 Step3, §8(프로젝트 로컬 메모리)→Task1(petclinic memory/)·Task2(시드 복사)·Task5(memory 참조), §9(변경 파일)→Task1~6, §10(리스크: slack_channel 실값/복사본 드리프트)→Task1 Step1·Task6. 전역 설정 폐기 반영(구 Task1 삭제). 누락 없음.

**Placeholder scan:** `<채널 id>` 은 Task1 Step1 에서 실값 확인 후 치환(없으면 `NEEDS_VALUE`+보고)하도록 절차화 — 방치 플레이스홀더 아님. `<판별지표>`/`<배타지표>` 는 의도된 제네릭 예시. TBD/TODO 없음.

**Type consistency:** 기술자 키(`name/match.services/repo/source_path/branch_prefix/slack_channel/memory{db,schema,vault,note_template}/observability{grafana,loki,prometheus}/differential_table`)가 Task1·2·7 파서 검증 전반 동일. `$TARGET.repo`/`source_path`/`slack_channel`/`memory.*`/`differential_table` 명명이 Task3·4·5 전반 일치.
```
