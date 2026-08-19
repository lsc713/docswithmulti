import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const intermediate = path.join(root, ".ua/intermediate");
const raw = JSON.parse(fs.readFileSync(path.join(intermediate, "batches.json"), "utf8"));
const batches = Array.isArray(raw) ? raw : raw.batches;

const complexity = lines => lines > 200 ? "complex" : lines >= 50 ? "moderate" : "simple";
const stem = p => path.basename(p).replace(/\.[^.]+$/, "");
const domainFromPath = p => {
  const parts = p.split("/");
  const wi = parts.indexOf("workstreams");
  if (wi >= 0 && parts[wi + 1]) return parts[wi + 1];
  const service = parts.find(x => x.endsWith("-service"));
  return service || "프로젝트";
};

function docSummary(file, result) {
  const p = file.path;
  const base = path.basename(p, ".md");
  const domain = domainFromPath(p);
  const phase = p.split("/").find(x => /^\d\d-/.test(x));
  const scope = phase ? `${domain} workstream의 ${phase} 단계` : `${domain} 영역`;
  const roles = [
    ["REQUIREMENTS", "요구사항과 완료 조건"], ["ROADMAP", "단계별 구현 순서와 의존성"],
    ["STATE", "현재 진행 상태와 후속 작업"], ["PLAN", "구현 작업, 검증 절차, 변경 파일"],
    ["SUMMARY", "구현 결과, 주요 결정, 검증 결과"], ["RESEARCH", "기술 조사, 제약, 권장 구현 방향"],
    ["VALIDATION", "요구사항 충족 여부와 자동화 검증 근거"], ["VERIFICATION", "완료 결과와 실행 검증 증거"],
    ["CONTEXT", "배경, 범위, 설계 선택"], ["DISCUSSION-LOG", "논의 과정과 결정 이력"],
    ["PATTERNS", "재사용할 구현 패턴과 적용 지침"], ["REVIEW-FIX", "review 지적과 수정 내역"],
    ["REVIEW", "구현 review 결과와 남은 위험"], ["UAT", "사용자 수용 테스트 시나리오와 결과"],
    ["COVERAGE", "요구사항 및 edge case coverage"],
  ];
  const role = roles.find(([key]) => base.includes(key))?.[1];
  if (role) return `${scope}의 ${role}을 정리한 문서입니다.`;
  const special = {
    "ARCHITECTURE": "서비스 경계, 요청·이벤트 흐름, 데이터 소유권을 설명하는 시스템 architecture 문서입니다.",
    "CONCERNS": "코드베이스의 기술 부채, 운영 위험, 보안 및 확장성 우려를 우선순위별로 정리한 문서입니다.",
    "CONVENTIONS": "Java/Spring, API, persistence, messaging 구현에 적용하는 코드 및 구조 convention을 정리한 문서입니다.",
    "INTEGRATIONS": "Kafka, MySQL, Redis, MinIO와 외부 연동 지점을 서비스별로 설명하는 integration 문서입니다.",
    "STACK": "Java 21, Spring Boot, React/Vite와 인프라 도구의 역할 및 버전을 정리한 기술 stack 문서입니다.",
    "STRUCTURE": "multi-service 저장소의 디렉터리 구성과 각 모듈 책임을 안내하는 codebase structure 문서입니다.",
    "TESTING": "단위·통합·E2E·load test 실행 방식과 test fixture 관례를 설명하는 testing 문서입니다.",
    "INGEST-CONFLICTS": "planning 문서 ingest 중 발견된 충돌과 해소 상태를 기록한 문서입니다.",
    "PROJECT": "프로젝트 목표, 기술 맥락, 핵심 제약과 현재 개발 방향을 요약한 planning 기준 문서입니다.",
    "CLAUDE": "multi-service commerce 프로젝트의 모듈 구성, 필수 설계 문서, 핵심 불변식과 작업 규칙을 안내합니다.",
  };
  if (special[base]) return special[base];
  const headings = (result.sections || []).slice(0, 3).map(x => x.heading).join(", ");
  return `${scope}을 설명하는 planning 문서이며 ${headings || "주요 결정과 실행 정보"}를 다룹니다.`;
}

function configSummary(file) {
  const p = file.path;
  if (p.includes("/classifications/") && p.endsWith(".json")) {
    try {
      const data = JSON.parse(fs.readFileSync(path.join(root, p), "utf8"));
      return `‘${data.title || stem(p)}’ 자료의 source path, 문서 유형, 신뢰도와 ingest override를 기록한 classification metadata입니다.`;
    } catch {}
  }
  if (p.endsWith(".edge-coverage.json")) return "consistency recovery 계획의 항목별 edge case coverage와 충족 상태를 추적하는 검증 metadata입니다.";
  if (p === ".env") return "Slack on-call 자동화와 외부 API 연동에 필요한 runtime 환경 변수 이름을 정의합니다.";
  if (p === "build.gradle") return "Java 21 multi-module Spring Boot 서비스에 공통 적용할 plugin, repository, dependency 및 test 설정을 정의합니다.";
  if (p === "settings.gradle") return "commerce 시스템을 구성하는 Spring Boot service module을 Gradle build에 포함하는 root project 설정입니다.";
  if (p.endsWith("/.planning/config.json") || p === ".planning/config.json") return "planning workflow의 단계, 문서 ingest, 자동화 동작을 제어하는 프로젝트 설정입니다.";
  if (p.endsWith("/config.json")) return `${domainFromPath(p)} workstream의 planning 동작과 phase 추적 옵션을 정의합니다.`;
  return `${path.basename(p)}에서 프로젝트 build 또는 planning 동작에 필요한 설정 값을 정의합니다.`;
}

function sqlSummary(file, result) {
  const names = (result.definitions || []).filter(x => x.kind === "table").map(x => x.name);
  const action = /__create_/i.test(file.path) ? "생성" : /__drop_/i.test(file.path) ? "제거" : /__add_|__align_|__replace_/i.test(file.path) ? "변경" : "관리";
  const targets = names.length ? names.join(", ") : stem(file.path).replace(/^V\d+__/, "").replaceAll("_", " ");
  return `${domainFromPath(file.path)} database에서 ${targets} schema를 ${action}하는 Flyway migration입니다.`;
}

function fileSummary(file, result) {
  const p = file.path;
  if (file.fileCategory === "docs") return docSummary(file, result);
  if (file.fileCategory === "config") return configSummary(file);
  if (file.fileCategory === "data" && p.endsWith(".sql")) return sqlSummary(file, result);
  if (p === ".github/workflows/loadtest-images.yml") return "load test용 application image를 build하고 registry에 게시하는 GitHub Actions pipeline을 정의합니다.";
  if (p === ".dockerignore") return "Docker build context에서 VCS metadata, build output, local cache와 불필요한 파일을 제외합니다.";
  if (p === "docker-compose.yml") return "Kafka, MySQL, Redis, MinIO와 commerce microservice를 로컬에서 함께 실행하도록 container, network, volume을 구성합니다.";
  if (p === "gradlew.bat") return "Windows에서 Gradle Wrapper를 실행하고 JVM 및 classpath 인자를 조립하는 generated launcher script입니다.";
  if (p === "test-compensation.sh") return "결제 보상 흐름에 필요한 service를 기동하고 실패·복구 시나리오와 database 상태를 검증하는 shell test harness입니다.";
  if (p === "test-e2e.sh") return "commerce service 전체를 대상으로 주문·결제·취소의 end-to-end 흐름을 준비하고 검증하는 shell test harness입니다.";
  return `${path.basename(p)}의 역할과 실행 동작을 구현하는 프로젝트 파일입니다.`;
}

function tagsFor(file) {
  const p = file.path;
  if (file.fileCategory === "docs") {
    const role = /PLAN/.test(p) ? "구현-계획" : /SUMMARY/.test(p) ? "구현-요약" : /RESEARCH/.test(p) ? "기술-조사" : /VERIFICATION|VALIDATION|UAT|COVERAGE|REVIEW/.test(p) ? "검증" : /REQUIREMENTS/.test(p) ? "요구사항" : /ROADMAP/.test(p) ? "로드맵" : /STATE/.test(p) ? "진행-상태" : "문서화";
    return ["문서화", "planning", role, domainFromPath(p)];
  }
  if (file.fileCategory === "data") return ["database", "migration", "schema-definition", domainFromPath(p)];
  if (file.fileCategory === "config") return p.includes("classifications") ? ["configuration", "metadata", "문서-분류", "ingest"] : ["configuration", "build-system", "프로젝트-설정"];
  if (p.includes("workflows")) return ["ci-cd", "containerization", "load-test", "deployment"];
  if (p === "docker-compose.yml") return ["orchestration", "infrastructure", "containerization", "로컬-환경"];
  if (p === ".dockerignore") return ["containerization", "infrastructure", "build-context"];
  if (p.endsWith(".sh") || p.endsWith(".bat")) return ["test", "script", "automation", "검증"];
  return ["infrastructure", "configuration", "deployment"];
}

function parentType(file) {
  const p = file.path;
  if (file.fileCategory === "config") return ["config", `config:${p}`];
  if (file.fileCategory === "docs") return ["document", `document:${p}`];
  if (file.fileCategory === "infra") {
    if (p.includes(".github/workflows/") || p.includes(".gitlab-ci") || p.includes("Jenkinsfile")) return ["pipeline", `pipeline:${p}`];
    if (p.endsWith(".tf") || p.endsWith(".tfvars")) return ["resource", `resource:${p}`];
    return ["service", `service:${p}`];
  }
  if (file.fileCategory === "data" && p.endsWith(".sql")) return ["table", `table:${p}:${stem(p)}`];
  if (file.fileCategory === "data" && /\.(graphql|proto|prisma)$/.test(p)) return ["schema", `schema:${p}`];
  return ["file", `file:${p}`];
}

function supplementSkipped(file) {
  const result = { path: file.path, language: file.language, fileCategory: file.fileCategory, totalLines: file.sizeLines, nonEmptyLines: file.sizeLines, functions: [], classes: [], exports: [], metrics: {} };
  if (file.language === "batch") {
    const lines = fs.readFileSync(path.join(root, file.path), "utf8").split(/\r?\n/);
    const labels = [];
    lines.forEach((line, i) => { const m = line.match(/^:([^:\s][^\s]*)/); if (m) labels.push({ name: m[1], startLine: i + 1 }); });
    labels.forEach((x, i) => { x.endLine = (labels[i + 1]?.startLine || lines.length + 1) - 1; x.params = []; });
    result.functions = labels;
  }
  return result;
}

function outputFor(batch) {
  const extraction = JSON.parse(fs.readFileSync(path.join(root, `.ua/tmp/ua-file-extract-results-${batch.batchIndex}.json`), "utf8"));
  const extractedByPath = new Map(extraction.results.map(x => [x.path, x]));
  const nodes = [], edges = [];
  for (const file of batch.files) {
    const result = extractedByPath.get(file.path) || supplementSkipped(file);
    const [type, id] = parentType(file);
    const parent = {
      id, type, name: path.basename(file.path), filePath: file.path,
      summary: fileSummary(file, result), tags: [...new Set(tagsFor(file))],
      complexity: complexity(result.nonEmptyLines ?? file.sizeLines),
    };
    if (file.path.endsWith("docker-compose.yml")) parent.languageNotes = "YAML service 정의와 healthcheck 의존성으로 local microservice topology를 구성합니다.";
    if (file.path.endsWith(".sql") && (result.definitions || []).length > 1) parent.languageNotes = "Flyway versioned migration으로 여러 relational table과 index를 함께 정의합니다.";
    nodes.push(parent);

    for (const def of (result.definitions || []).filter(x => x.kind === "table")) {
      const defId = `table:${file.path}:${def.name}`;
      if (defId === id || nodes.some(x => x.id === defId)) continue;
      nodes.push({
        id: defId, type: "table", name: def.name, filePath: file.path,
        summary: `${def.name} table은 ${def.fields?.length ? def.fields.slice(0, 6).join(", ") + " 등" : "업무 데이터와 상태"}을 저장합니다.`,
        tags: ["database", "table", "schema-definition", domainFromPath(file.path)],
        complexity: complexity((def.endLine || 0) - (def.startLine || 0) + 1),
      });
      edges.push({ source: id, target: defId, type: "defines_schema", direction: "forward", weight: 0.8 });
    }

    const exported = new Set((result.exports || []).map(x => x.name));
    for (const fn of result.functions || []) {
      const len = (fn.endLine || fn.startLine) - fn.startLine + 1;
      if (len < 10 && !exported.has(fn.name)) continue;
      const fnId = `function:${file.path}:${fn.name}`;
      nodes.push({
        id: fnId, type: "function", name: fn.name, filePath: file.path, lineRange: [fn.startLine, fn.endLine],
        summary: `${fn.name} 함수는 ${file.path.includes("test") ? "test 환경 준비 또는 검증 단계를 수행" : "script 실행 흐름의 한 단계를 처리"}합니다.`,
        tags: ["함수", "automation", file.path.includes("test") ? "test-helper" : "script"], complexity: complexity(len),
      });
      edges.push({ source: id, target: fnId, type: "contains", direction: "forward", weight: 1.0 });
      if (exported.has(fn.name)) edges.push({ source: id, target: fnId, type: "exports", direction: "forward", weight: 0.8 });
    }
    for (const cls of result.classes || []) {
      const len = cls.endLine - cls.startLine + 1;
      if (len < 20 && (cls.methods || []).length < 2 && !exported.has(cls.name)) continue;
      const clsId = `class:${file.path}:${cls.name}`;
      nodes.push({ id: clsId, type: "class", name: cls.name, filePath: file.path, lineRange: [cls.startLine, cls.endLine], summary: `${cls.name} class는 ${path.basename(file.path)}의 주요 상태와 동작을 캡슐화합니다.`, tags: ["class", "도메인-로직", "service"], complexity: complexity(len) });
      edges.push({ source: id, target: clsId, type: "contains", direction: "forward", weight: 1.0 });
      if (exported.has(cls.name)) edges.push({ source: id, target: clsId, type: "exports", direction: "forward", weight: 0.8 });
    }

    if (file.fileCategory === "code") {
      for (const target of batch.batchImportData?.[file.path] || []) {
        edges.push({ source: id, target: `file:${target}`, type: "imports", direction: "forward", weight: 0.7 });
      }
    }
  }
  return { nodes, edges, skipped: extraction.filesSkipped || [] };
}

for (const batch of batches.filter(({ batchIndex }) => batchIndex >= 38 && batchIndex <= 73)) {
  const graph = outputFor(batch);
  const parts = Math.ceil(Math.max(graph.nodes.length / 60, graph.edges.length / 120));
  if (parts <= 1) {
    fs.writeFileSync(path.join(intermediate, `batch-${batch.batchIndex}.json`), JSON.stringify({ nodes: graph.nodes, edges: graph.edges }, null, 2) + "\n");
  } else {
    const sortedFiles = [...batch.files].sort((a, b) => a.path.localeCompare(b.path));
    const chunkSize = Math.ceil(sortedFiles.length / parts);
    for (let k = 0; k < parts; k++) {
      const paths = new Set(sortedFiles.slice(k * chunkSize, (k + 1) * chunkSize).map(x => x.path));
      const partNodes = graph.nodes.filter(x => paths.has(x.filePath));
      const ids = new Set(partNodes.map(x => x.id));
      const partEdges = graph.edges.filter(x => ids.has(x.source));
      fs.writeFileSync(path.join(intermediate, `batch-${batch.batchIndex}-part-${k + 1}.json`), JSON.stringify({ nodes: partNodes, edges: partEdges }, null, 2) + "\n");
    }
  }
  console.log(`${batch.batchIndex}: nodes=${graph.nodes.length}, edges=${graph.edges.length}, parts=${parts}, skipped=${graph.skipped.join(",") || "-"}`);
}
