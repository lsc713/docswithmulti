import fs from 'node:fs';
import path from 'node:path';

const root = process.cwd();
const ua = path.join(root, fs.existsSync(path.join(root, '.understand-anything')) ? '.understand-anything' : '.ua');
const batches = JSON.parse(fs.readFileSync(path.join(ua, 'intermediate/batches.json'), 'utf8')).batches;

const clean = value => String(value || '').replace(/[`#*_<>]/g, '').replace(/\s+/g, ' ').trim();
const kebab = value => clean(value).toLowerCase().replace(/[^\p{L}\p{N}]+/gu, '-').replace(/^-|-$/g, '') || '항목';
const basename = file => path.posix.basename(file);

function readSource(file) {
  try { return fs.readFileSync(path.join(root, file), 'utf8'); } catch { return ''; }
}

function titleFrom(file, source) {
  const heading = source.match(/^#{1,3}\s+(.+)$/m)?.[1];
  return clean(heading || basename(file).replace(/\.[^.]+$/, '').replace(/[-_]/g, ' '));
}

function topic(file) {
  const f = file.toLowerCase();
  const topics = [
    ['cancel-outbox-redrive', '취소 outbox redrive'], ['cancel-restore', '취소 재고 복원'],
    ['settlement', '정산'], ['product-detail', '상품 상세 조회'], ['product-catalog', '상품 카탈로그'],
    ['merchant-limit', '가맹점 한도'], ['risk-management', '위험 관리'], ['payment', '결제'],
    ['order', '주문'], ['kafka', 'Kafka 메시징'], ['k3s', 'k3s 배포'], ['load-test', '부하 테스트'],
    ['observability', '관측성'], ['security', '보안'], ['frontend', 'frontend'], ['product', '상품'],
    ['user', '사용자 인증'], ['terraform', 'Terraform 인프라']
  ];
  return topics.find(([key]) => f.includes(key))?.[1] || '프로젝트 구현';
}

function complexity(lines, structural = 0) {
  if (lines > 200 || structural > 12) return 'complex';
  if (lines >= 50 || structural > 4) return 'moderate';
  return 'simple';
}

function fileType(file, category, language, source) {
  if (category === 'docs') return ['document', `document:${file}`];
  if (category === 'config') return ['config', `config:${file}`];
  if (category === 'infra') {
    if (/\.github\/workflows|\.gitlab-ci|jenkinsfile|\.circleci/i.test(file)) return ['pipeline', `pipeline:${file}`];
    if (/\.tf(vars)?$|cloudformation|vagrantfile/i.test(file)) return ['resource', `resource:${file}`];
    return ['service', `service:${file}`];
  }
  if (category === 'data') {
    if (/\.sql$/i.test(file) && /create\s+table/i.test(source)) return ['table', `table:${file}:${kebab(basename(file).replace(/\.sql$/i, ''))}`];
    if (/openapi|swagger/i.test(file)) return ['endpoint', `endpoint:${file}`];
    return ['schema', `schema:${file}`];
  }
  return ['file', `file:${file}`];
}

function fileSummary(file, category, language, source, result) {
  const title = titleFrom(file, source);
  const subject = topic(file);
  const name = basename(file).toLowerCase();
  if (category === 'docs') {
    const kind = /plan/.test(name) ? '구현 계획' : /summary/.test(name) ? '구현 결과 요약' : /verification|validation/.test(name) ? '검증 기록' : /design|spec/.test(name) ? '설계 명세' : /review/.test(name) ? '검토 기록' : /readme/.test(name) ? '사용 안내' : '기술 문서';
    return `${title}은(는) ${subject}의 ${kind}로, 관련 범위와 결정 사항을 정리한다.`;
  }
  if (category === 'config') {
    if (name === 'package.json') return `${subject} frontend의 npm 의존성과 개발·빌드·검증 script를 정의한다.`;
    if (/application\.ya?ml/.test(name)) return `${subject} Spring Boot service의 port, datasource, Kafka 및 운영 설정을 구성한다.`;
    if (/gradle/.test(name)) return `${subject} module의 Gradle plugin, dependency와 test/build 설정을 정의한다.`;
    return `${subject}에 사용되는 ${language} configuration 값과 도구 동작을 정의한다.`;
  }
  if (category === 'infra') {
    if (/\.tf$/.test(file)) return `${subject} 환경에 필요한 AWS resource와 연결 설정을 Terraform으로 선언한다.`;
    if (/docker-compose/.test(name)) return `${subject} 구성 요소를 함께 실행하기 위한 container service와 network 설정을 정의한다.`;
    if (/\.ya?ml$/.test(name)) return `${subject} workload와 운영 resource를 배포하기 위한 infrastructure manifest다.`;
    return `${subject} 환경을 준비하거나 배포하는 infrastructure 자동화 파일이다.`;
  }
  if (category === 'markup') {
    if (file.startsWith('docs/')) return `${subject}의 구성과 실행 흐름을 브라우저에서 보여 주는 HTML/CSS 기반 architecture 시각화다.`;
    return `${subject} UI의 문서 구조와 style을 정의하는 markup 자산이다.`;
  }
  if (category === 'script') return `${subject} 작업을 반복 실행하기 위한 command-line 자동화 script다.`;
  const names = [...(result?.classes || []), ...(result?.functions || [])].map(x => x.name).slice(0, 3);
  if (/application\.java$/.test(name)) return `${subject} Spring Boot service를 시작하는 application entry point다.`;
  if (/repository\.java$/.test(name)) return `${subject} domain entity의 조회와 저장을 담당하는 Spring Data repository다.`;
  if (/config\.java$/.test(name)) return `${subject} service에서 사용하는 Spring bean과 runtime integration 설정을 구성한다.`;
  if (/dto|request|response|payload|message/.test(name)) return `${subject} 경계에서 전달되는 요청·응답 또는 event payload 구조를 정의한다.`;
  if (names.length) return `${subject} 기능을 구현하며 ${names.join(', ')} 구조를 제공한다.`;
  return `${subject} 기능을 지원하는 ${language} source 또는 실행 자산이다.`;
}

function fileTags(file, category, language) {
  const f = file.toLowerCase();
  const tags = [];
  if (category === 'docs') tags.push('문서');
  else if (category === 'config') tags.push('configuration');
  else if (category === 'infra') tags.push('infrastructure', /\.tf/.test(f) ? 'terraform' : 'deployment');
  else if (category === 'markup') tags.push('markup', f.endsWith('.css') ? 'styling' : 'visualization');
  else tags.push(/test|spec/.test(f) ? 'test' : 'source-code');
  if (/application\.java$/.test(f)) tags.push('entry-point', 'spring-boot');
  else if (/repository/.test(f)) tags.push('persistence', 'spring-data');
  else if (/controller|\.http$/.test(f)) tags.push('api-handler', 'http');
  else if (/config/.test(f)) tags.push('configuration', 'integration');
  else if (/dto|request|response|payload|message/.test(f)) tags.push('data-transfer', 'serialization');
  else if (/plan/.test(f)) tags.push('구현-계획', '개발-프로세스');
  else if (/summary/.test(f)) tags.push('구현-요약', '변경-기록');
  else if (/verification|validation|review/.test(f)) tags.push('검증', '품질');
  else tags.push(kebab(topic(file)), kebab(language));
  return [...new Set(tags)].slice(0, 5).concat(['project-artifact']).slice(0, 3).length >= 3
    ? [...new Set(tags.concat('project-artifact'))].slice(0, 5)
    : ['project-artifact', '프로젝트-구성', '참조'];
}

function symbolSummary(kind, name, file) {
  const subject = topic(file);
  if (kind === 'class') {
    if (/Repository/.test(name)) return `${subject} data 접근 계약 또는 persistence adapter를 정의한다.`;
    if (/Config/.test(name)) return `${subject} runtime에 필요한 Spring 구성 요소를 조립한다.`;
    if (/Application/.test(name)) return `${subject} service의 Spring Boot 실행을 시작한다.`;
    if (/Request|Response|Payload|Message|Dto/.test(name)) return `${subject} 경계에서 교환되는 data 구조를 표현한다.`;
    if (/Entity/.test(name)) return `${subject} 상태를 database record로 매핑하는 JPA entity다.`;
    return `${subject}의 ${name} 책임과 관련 동작을 캡슐화한다.`;
  }
  return `${subject} 흐름에서 ${name} 작업을 수행한다.`;
}

function symbolTags(kind, name) {
  const tags = [kind];
  if (/Repository/.test(name)) tags.push('persistence', 'data-access');
  else if (/Config/.test(name)) tags.push('configuration', 'spring');
  else if (/Request|Response|Payload|Message|Dto/.test(name)) tags.push('data-transfer', 'serialization');
  else if (/Entity/.test(name)) tags.push('data-model', 'jpa');
  else if (/Application/.test(name)) tags.push('entry-point', 'spring-boot');
  else tags.push('business-logic', 'component');
  return tags.slice(0, 5);
}

function addUnique(list, item, ids) {
  if (!ids.has(item.id)) { ids.add(item.id); list.push(item); }
}

function analyzeBatch(batch) {
  const extraction = JSON.parse(fs.readFileSync(path.join(ua, 'tmp', `ua-file-extract-results-${batch.batchIndex}.json`), 'utf8'));
  const byPath = new Map(extraction.results.map(r => [r.path, r]));
  const nodes = [], edges = [], nodeIds = new Set(), edgeKeys = new Set();
  const addEdge = edge => {
    if (edge.source === edge.target) return;
    const key = `${edge.source}|${edge.target}|${edge.type}`;
    if (!edgeKeys.has(key)) { edgeKeys.add(key); edges.push({...edge, direction: 'forward'}); }
  };

  for (const meta of batch.files) {
    const result = byPath.get(meta.path);
    const source = readSource(meta.path);
    const [type, id] = fileType(meta.path, meta.fileCategory, meta.language, source);
    const structural = (result?.functions?.length || 0) + (result?.classes?.length || 0) + (result?.resources?.length || 0);
    addUnique(nodes, {
      id, type, name: basename(meta.path), filePath: meta.path,
      summary: fileSummary(meta.path, meta.fileCategory, meta.language, source, result),
      tags: fileTags(meta.path, meta.fileCategory, meta.language),
      complexity: complexity(result?.nonEmptyLines ?? meta.sizeLines, structural),
      ...(meta.language === 'java' ? {languageNotes: 'Java 21과 Spring Boot 관례를 따르는 구성 요소다.'} : {})
    }, nodeIds);

    if ((meta.fileCategory === 'code' || meta.fileCategory === 'script') && result) {
      const exported = new Set((result.exports || []).map(x => x.name));
      for (const [kind, items] of [['function', result.functions || []], ['class', result.classes || []]]) {
        for (const item of items) {
          const lines = Math.max(1, (item.endLine || item.startLine || 1) - (item.startLine || 1) + 1);
          const significant = exported.has(item.name) || lines >= (kind === 'function' ? 10 : 20) || (kind === 'class' && (item.methods?.length || 0) >= 2);
          if (!significant) continue;
          const sid = `${kind}:${meta.path}:${item.name}`;
          addUnique(nodes, {id:sid, type:kind, name:item.name, filePath:meta.path, lineRange:[item.startLine || 1,item.endLine || item.startLine || 1], summary:symbolSummary(kind,item.name,meta.path), tags:symbolTags(kind,item.name), complexity:complexity(lines,(item.methods?.length || 0))}, nodeIds);
          addEdge({source:id,target:sid,type:'contains',weight:1.0});
          if (exported.has(item.name)) addEdge({source:id,target:sid,type:'exports',weight:0.8});
        }
      }
    }

    for (const resource of result?.resources || []) {
      const rid = `resource:${meta.path}:${resource.name}`;
      addUnique(nodes, {id:rid,type:'resource',name:resource.name,filePath:meta.path,lineRange:[resource.startLine || 1,resource.endLine || resource.startLine || 1],summary:`${resource.kind || 'Terraform resource'} ${resource.name}의 infrastructure 구성을 선언한다.`,tags:['infrastructure','terraform','resource'],complexity:complexity((resource.endLine || 1)-(resource.startLine || 1)+1)}, nodeIds);
      addEdge({source:id,target:rid,type:'contains',weight:1.0});
    }
  }

  for (const meta of batch.files) {
    if (meta.fileCategory !== 'code') continue;
    const source = `file:${meta.path}`;
    for (const targetPath of batch.batchImportData?.[meta.path] || []) addEdge({source,target:`file:${targetPath}`,type:'imports',weight:0.7});
  }
  return {nodes, edges, skipped: extraction.filesSkipped || []};
}

function writeBatch(index, batchFiles, fragment) {
  const old = fs.readdirSync(path.join(ua, 'intermediate')).filter(x => x === `batch-${index}.json` || x.startsWith(`batch-${index}-part-`));
  for (const name of old) fs.unlinkSync(path.join(ua, 'intermediate', name));
  if (fragment.nodes.length <= 60 && fragment.edges.length <= 120) {
    fs.writeFileSync(path.join(ua, 'intermediate', `batch-${index}.json`), JSON.stringify({nodes:fragment.nodes,edges:fragment.edges}, null, 2) + '\n');
    return 1;
  }
  const sorted = [...batchFiles].sort((a,b) => a.path.localeCompare(b.path));
  let parts = Math.ceil(Math.max(fragment.nodes.length / 60, fragment.edges.length / 120));
  while (parts < sorted.length) {
    const size = Math.ceil(sorted.length / parts);
    const oversized = Array.from({length:parts}, (_, p) => {
      const paths = new Set(sorted.slice(p*size,(p+1)*size).map(x => x.path));
      const ids = new Set(fragment.nodes.filter(n => paths.has(n.filePath)).map(n => n.id));
      return ids.size > 60 || fragment.edges.filter(e => ids.has(e.source)).length > 120;
    }).some(Boolean);
    if (!oversized) break;
    parts++;
  }
  const size = Math.ceil(sorted.length / parts);
  for (let p=0;p<parts;p++) {
    const paths = new Set(sorted.slice(p*size,(p+1)*size).map(x => x.path));
    const nodes = fragment.nodes.filter(n => paths.has(n.filePath));
    const ids = new Set(nodes.map(n => n.id));
    const edges = fragment.edges.filter(e => ids.has(e.source));
    fs.writeFileSync(path.join(ua, 'intermediate', `batch-${index}-part-${p+1}.json`), JSON.stringify({nodes,edges}, null, 2) + '\n');
  }
  return parts;
}

const report = [];
for (let index=74; index<=109; index++) {
  const batch = batches.find(b => b.batchIndex === index);
  if (!batch) throw new Error(`원본 batch ${index}가 없습니다.`);
  const fragment = analyzeBatch(batch);
  const parts = writeBatch(index, batch.files, fragment);
  report.push({index, parts, nodes:fragment.nodes.length, edges:fragment.edges.length, skipped:fragment.skipped});
}
console.log(JSON.stringify(report, null, 2));
