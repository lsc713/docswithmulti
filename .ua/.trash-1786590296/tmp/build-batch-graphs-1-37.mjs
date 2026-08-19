import fs from 'node:fs';
import path from 'node:path';

const root = process.cwd();
const uaDir = path.join(root, fs.existsSync(path.join(root, '.understand-anything')) ? '.understand-anything' : '.ua');
const intermediate = path.join(uaDir, 'intermediate');
const tmp = path.join(uaDir, 'tmp');
const raw = JSON.parse(fs.readFileSync(path.join(intermediate, 'batches.json'), 'utf8'));
const batches = Array.isArray(raw) ? raw : raw.batches;

const complexity = lines => lines > 200 ? 'complex' : lines >= 50 ? 'moderate' : 'simple';
const baseName = filePath => path.posix.basename(filePath);
const stem = filePath => baseName(filePath).replace(/\.[^.]+$/, '');
const serviceTag = filePath => {
  const first = filePath.split('/')[0];
  return first.endsWith('-service') || first === 'api-gateway' || first === 'frontend' || first === 'k6' ? first : 'shared';
};
const isTest = filePath => /(^|\/)(test|tests)\//i.test(filePath) || /(Test|Tests)\.java$|\.(test|spec)\.[jt]sx?$|test\.js$/i.test(filePath);
const unique = values => [...new Set(values)].slice(0, 5);

function role(filePath, name = stem(filePath)) {
  const p = filePath.toLowerCase();
  const n = name.toLowerCase();
  if (isTest(filePath)) return 'test';
  if (n.endsWith('exception') || p.includes('/exception/')) return 'exception';
  if (n.endsWith('controller') || p.includes('/controller/')) return 'api-handler';
  if (n.endsWith('scheduler') || n.endsWith('worker')) return 'scheduler';
  if (n.endsWith('repository') || n.endsWith('jparepository') || n.endsWith('repositoryimpl')) return 'repository';
  if (n.endsWith('service') || p.includes('/service/')) return 'service';
  if (n.endsWith('usecase') || p.includes('/usecase/')) return 'use-case';
  if (n.endsWith('config') || p.includes('/config/')) return 'configuration';
  if (n.endsWith('request') || n.endsWith('response') || p.includes('/dto/')) return 'dto';
  if (p.includes('/entity/') || p.includes('/domain/model/')) return 'data-model';
  if (n.endsWith('policy') || p.includes('/policy/')) return 'policy';
  if (p.includes('/authz/') || n.includes('authoriz')) return 'authorization';
  if (p.includes('/messaging/') || n.includes('outbox') || n.includes('publisher')) return 'messaging';
  if (p.includes('/persistence/')) return 'persistence';
  if (p.startsWith('frontend/')) return 'component';
  if (p.startsWith('k6/')) return 'load-test';
  if (n.endsWith('application') || /\/main\.[jt]sx?$/.test(p)) return 'entry-point';
  return 'domain-logic';
}

function fileSummary(file, result) {
  const name = stem(file.path);
  const r = role(file.path, name);
  const label = name.replace(/([a-z0-9])([A-Z])/g, '$1 $2');
  const service = serviceTag(file.path);
  const count = (result.functions?.length || 0) + (result.classes?.length || 0);
  switch (r) {
    case 'test': return `${label}의 핵심 동작과 경계 조건을 검증하는 자동화 테스트다.`;
    case 'exception': return `${service}에서 ${label} 오류 상황을 명시적으로 전달하는 예외 타입이다.`;
    case 'api-handler': return `${service}의 ${label} HTTP 요청을 받아 application 계층으로 연결하고 응답을 구성한다.`;
    case 'scheduler': return `${service}에서 ${label} 백그라운드 작업을 주기적으로 실행하고 처리 상태를 관리한다.`;
    case 'repository': return `${service}의 ${label} 저장소 계약 또는 persistence 구현으로 domain 데이터 접근을 캡슐화한다.`;
    case 'service': return `${service}의 ${label} application/domain 규칙과 협력 객체 호출을 조정한다.`;
    case 'use-case': return `${service}에서 ${label} 유스케이스의 입력·출력 계약과 실행 경계를 정의한다.`;
    case 'configuration': return `${service}의 ${label} Spring 구성과 runtime 의존성 조립을 담당한다.`;
    case 'dto': return `${service}의 ${label} API 또는 application 계층 데이터를 전달하는 DTO다.`;
    case 'data-model': return `${service}의 ${label} domain 상태와 불변식을 표현하는 데이터 모델이다.`;
    case 'policy': return `${service}의 ${label} domain 판단 규칙을 한곳에 모아 재사용한다.`;
    case 'authorization': return `${service}의 ${label} 권한 조건을 검증해 허용되지 않은 작업을 차단한다.`;
    case 'messaging': return `${service}의 ${label} event/outbox 메시지 흐름을 생성하거나 전달한다.`;
    case 'persistence': return `${service}에서 ${label} domain 객체와 저장소 간 변환 및 영속화를 담당한다.`;
    case 'component': return `${name} UI 구성요소와 사용자 상호작용을 구현하는 frontend 모듈이다.`;
    case 'load-test': return `${name} 시나리오와 지표를 정의해 commerce API의 부하 특성을 검증한다.`;
    case 'entry-point': return `${service} 애플리케이션을 부트스트랩하고 runtime 진입점을 제공한다.`;
    default: return `${service}의 ${label} 관련 domain/application 동작을 구현하며 ${count}개의 주요 구조를 포함한다.`;
  }
}

function nodeTags(filePath, name) {
  const r = role(filePath, name);
  const layer = filePath.includes('/presentation/') ? 'presentation' : filePath.includes('/application/') ? 'application' : filePath.includes('/domain/') ? 'domain' : filePath.includes('/infrastructure/') ? 'infrastructure' : null;
  return unique([serviceTag(filePath), r, layer, filePath.endsWith('.java') ? 'java' : filePath.endsWith('.jsx') ? 'react' : filePath.endsWith('.js') ? 'javascript' : null, isTest(filePath) ? 'test' : null].filter(Boolean));
}

function classSummary(filePath, cls) {
  const r = role(filePath, cls.name);
  if (r === 'exception') return `${cls.name} 오류를 타입으로 표현해 호출자가 실패 원인을 구분할 수 있게 한다.`;
  if (r === 'dto') return `${cls.name} 구조로 계층 간 데이터를 명확하게 전달한다.`;
  if (r === 'repository') return `${cls.name} 저장소의 조회·저장 연산을 정의하거나 구현한다.`;
  if (r === 'api-handler') return `${cls.name} API 요청을 검증하고 해당 유스케이스로 위임한다.`;
  if (r === 'test') return `${cls.name} 대상의 기대 동작과 실패 조건을 검증한다.`;
  if (r === 'data-model') return `${cls.name} domain 상태와 관련 행위를 캡슐화한다.`;
  return `${cls.name}가 담당하는 ${r} 책임과 협력 객체 사용을 캡슐화한다.`;
}

function functionSummary(filePath, fn, ownerNames) {
  const name = fn.name;
  if (ownerNames.has(name)) return `${name} 인스턴스의 필수 의존성과 초기 상태를 설정한다.`;
  if (isTest(filePath)) return `${name} 시나리오의 기대 결과를 검증한다.`;
  const lower = name.toLowerCase();
  if (/^(get|find|load|read|list|search|inspect|status|exists|has|is)/.test(lower)) return `${name} 조건에 맞는 상태나 데이터를 조회해 반환한다.`;
  if (/^(create|new|add|save|register|issue)/.test(lower)) return `${name} 입력을 검증하고 새 상태 또는 데이터를 생성·저장한다.`;
  if (/^(update|change|set|apply|restore)/.test(lower)) return `${name} 대상 상태를 규칙에 따라 갱신한다.`;
  if (/^(delete|remove|clear)/.test(lower)) return `${name} 대상 데이터를 안전하게 제거하거나 초기화한다.`;
  if (/^(validate|verify|authorize|check|require)/.test(lower)) return `${name} 요청이 domain 및 권한 조건을 충족하는지 검증한다.`;
  if (/^(cancel|compensate|refund)/.test(lower)) return `${name} 취소 또는 보상 흐름을 실행하고 결과 상태를 반영한다.`;
  if (/^(publish|send|dispatch|emit)/.test(lower)) return `${name} 처리 결과를 event 또는 외부 메시지로 발행한다.`;
  if (/^(map|to|from|parse|convert)/.test(lower)) return `${name} 입력 데이터를 대상 계층의 표현으로 변환한다.`;
  if (/^(handle|process|execute|run|perform|on)/.test(lower)) return `${name} 작업 흐름을 실행하고 협력 객체 호출을 조정한다.`;
  return `${name} 관련 규칙을 수행하고 계산된 결과를 반환한다.`;
}

function makeFileNode(file, result) {
  let type = 'file';
  if (file.fileCategory === 'config') type = 'config';
  else if (file.fileCategory === 'docs') type = 'document';
  else if (file.fileCategory === 'infra') type = /Dockerfile|docker-compose|k8s|kubernetes/i.test(file.path) ? 'service' : /\.github\/workflows|gitlab-ci|Jenkinsfile|circleci/i.test(file.path) ? 'pipeline' : 'resource';
  else if (file.fileCategory === 'data') type = /\.(graphql|proto|prisma)$/i.test(file.path) ? 'schema' : /\.(ya?ml|json)$/i.test(file.path) && /openapi|swagger/i.test(file.path) ? 'endpoint' : 'table';
  const id = `${type}:${file.path}`;
  let summary = fileSummary(file, result);
  let tags = nodeTags(file.path, stem(file.path));
  if (file.fileCategory === 'infra' && /Dockerfile/i.test(file.path)) {
    const stages = result.services?.length || 0;
    summary = `${serviceTag(file.path)}를 Java 21 runtime image로 빌드하는 ${stages > 1 ? 'multi-stage ' : ''}Docker 구성이다.`;
    tags = ['containerization', 'infrastructure', 'deployment', serviceTag(file.path)];
  }
  return { id, type, name: baseName(file.path), filePath: file.path, summary, tags, complexity: complexity(result.nonEmptyLines ?? file.sizeLines) };
}

function exportedNames(result) {
  return new Set((result.exports || []).map(item => item.name));
}

function graphFor(batch, extraction) {
  const nodes = [];
  const edges = [];
  const resultByPath = new Map(extraction.results.map(result => [result.path, result]));
  for (const file of batch.files) {
    const result = resultByPath.get(file.path) || { path: file.path, nonEmptyLines: file.sizeLines, functions: [], classes: [], exports: [] };
    const fileNode = makeFileNode(file, result);
    nodes.push(fileNode);
    const exports = exportedNames(result);
    const ownerNames = new Set((result.classes || []).map(cls => cls.name));

    const classByName = new Map();
    for (const cls of result.classes || []) {
      const span = Math.max(1, cls.endLine - cls.startLine + 1);
      if (!(exports.has(cls.name) || span >= 20 || (cls.methods?.length || 0) >= 2)) continue;
      const previous = classByName.get(cls.name);
      if (!previous || span > previous.endLine - previous.startLine + 1) classByName.set(cls.name, cls);
    }
    for (const cls of classByName.values()) {
      const id = `class:${file.path}:${cls.name}`;
      nodes.push({ id, type: 'class', name: cls.name, filePath: file.path, lineRange: [cls.startLine, cls.endLine], summary: classSummary(file.path, cls), tags: unique([...nodeTags(file.path, cls.name), 'class']), complexity: complexity(cls.endLine - cls.startLine + 1) });
      edges.push({ source: fileNode.id, target: id, type: 'contains', direction: 'forward', weight: 1.0 });
      if (exports.has(cls.name)) edges.push({ source: fileNode.id, target: id, type: 'exports', direction: 'forward', weight: 0.8 });
    }

    const functionByName = new Map();
    for (const fn of result.functions || []) {
      const span = Math.max(1, fn.endLine - fn.startLine + 1);
      if (!(exports.has(fn.name) || span >= 10)) continue;
      const previous = functionByName.get(fn.name);
      if (!previous || span > previous.endLine - previous.startLine + 1) functionByName.set(fn.name, fn);
    }
    for (const fn of functionByName.values()) {
      const id = `function:${file.path}:${fn.name}`;
      nodes.push({ id, type: 'function', name: fn.name, filePath: file.path, lineRange: [fn.startLine, fn.endLine], summary: functionSummary(file.path, fn, ownerNames), tags: unique([...nodeTags(file.path, fn.name), 'function']), complexity: complexity(fn.endLine - fn.startLine + 1) });
      edges.push({ source: fileNode.id, target: id, type: 'contains', direction: 'forward', weight: 1.0 });
      if (exports.has(fn.name)) edges.push({ source: fileNode.id, target: id, type: 'exports', direction: 'forward', weight: 0.8 });
    }

    for (const target of batch.batchImportData[file.path] || []) {
      edges.push({ source: fileNode.id, target: `file:${target}`, type: 'imports', direction: 'forward', weight: 0.7 });
    }

    for (const svc of result.services || []) {
      const id = `service:${file.path}:${svc.name}`;
      nodes.push({ id, type: 'service', name: svc.name, filePath: file.path, lineRange: [svc.startLine, svc.endLine], summary: `${svc.image || svc.name} 기반 ${svc.name} container stage를 정의한다.`, tags: ['containerization', 'docker-stage', 'infrastructure'], complexity: complexity(svc.endLine - svc.startLine + 1) });
      edges.push({ source: fileNode.id, target: id, type: 'contains', direction: 'forward', weight: 1.0 });
    }
    for (const endpoint of result.endpoints || []) {
      const name = `${endpoint.method || ''}${endpoint.path || endpoint.name || ''}`;
      const id = `endpoint:${file.path}:${name}`;
      nodes.push({ id, type: 'endpoint', name, filePath: file.path, summary: `${name} API endpoint 계약을 정의한다.`, tags: ['api-schema', 'endpoint', 'contract'], complexity: 'simple' });
      edges.push({ source: fileNode.id, target: id, type: 'contains', direction: 'forward', weight: 1.0 });
    }
    for (const resource of result.resources || []) {
      const id = `resource:${file.path}:${resource.name}`;
      nodes.push({ id, type: 'resource', name: resource.name, filePath: file.path, summary: `${resource.kind || 'infrastructure'} ${resource.name} resource를 선언한다.`, tags: ['infrastructure', 'resource', 'deployment'], complexity: 'simple' });
      edges.push({ source: fileNode.id, target: id, type: 'contains', direction: 'forward', weight: 1.0 });
    }
    for (const definition of result.definitions || []) {
      if (!/proto|graphql/i.test(file.language)) continue;
      const id = `schema:${file.path}:${definition.name}`;
      nodes.push({ id, type: 'schema', name: definition.name, filePath: file.path, summary: `${definition.kind || 'schema'} ${definition.name} 데이터 계약을 정의한다.`, tags: ['schema-definition', 'data-contract', 'api-schema'], complexity: 'simple' });
      edges.push({ source: fileNode.id, target: id, type: 'contains', direction: 'forward', weight: 1.0 });
    }
  }
  return { nodes, edges };
}

function writeBatch(batch, graph) {
  const index = batch.batchIndex;
  for (const name of fs.readdirSync(intermediate)) {
    if (new RegExp(`^batch-${index}(?:-part-\\d+)?\\.json$`).test(name)) fs.unlinkSync(path.join(intermediate, name));
  }
  const parts = Math.ceil(Math.max(graph.nodes.length / 60, graph.edges.length / 120, 1));
  const sortedFiles = [...batch.files].sort((a, b) => a.path.localeCompare(b.path));
  const groupSize = Math.ceil(sortedFiles.length / parts);
  const written = [];
  for (let part = 0; part < parts; part++) {
    const paths = new Set(sortedFiles.slice(part * groupSize, (part + 1) * groupSize).map(file => file.path));
    if (!paths.size) continue;
    const nodes = graph.nodes.filter(node => paths.has(node.filePath));
    const ids = new Set(nodes.map(node => node.id));
    const edges = graph.edges.filter(edge => ids.has(edge.source));
    const fragment = { nodes, edges };
    const filename = parts === 1 ? `batch-${index}.json` : `batch-${index}-part-${part + 1}.json`;
    fs.writeFileSync(path.join(intermediate, filename), `${JSON.stringify(fragment, null, 2)}\n`);
    written.push({ filename, nodes: nodes.length, edges: edges.length });
  }
  return written;
}

const report = [];
for (const batch of batches.filter(({ batchIndex }) => batchIndex >= 1 && batchIndex <= 37)) {
  const extraction = JSON.parse(fs.readFileSync(path.join(tmp, `ua-file-extract-results-${batch.batchIndex}.json`), 'utf8'));
  if (!extraction.scriptCompleted) throw new Error(`Batch ${batch.batchIndex}: extraction did not complete`);
  const graph = graphFor(batch, extraction);
  const expectedImports = batch.files.reduce((sum, file) => sum + (batch.batchImportData[file.path]?.length || 0), 0);
  const actualImports = graph.edges.filter(edge => edge.type === 'imports').length;
  if (expectedImports !== actualImports) throw new Error(`Batch ${batch.batchIndex}: imports ${actualImports}/${expectedImports}`);
  const ids = graph.nodes.map(node => node.id);
  if (new Set(ids).size !== ids.length) throw new Error(`Batch ${batch.batchIndex}: duplicate node ids`);
  report.push({ batchIndex: batch.batchIndex, files: batch.files.length, skipped: extraction.filesSkipped || [], nodes: graph.nodes.length, edges: graph.edges.length, imports: actualImports, parts: writeBatch(batch, graph) });
}
fs.writeFileSync(path.join(tmp, 'batch-graphs-1-37-report.json'), `${JSON.stringify(report, null, 2)}\n`);
