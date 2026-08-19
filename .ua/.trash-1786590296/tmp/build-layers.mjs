import fs from "node:fs";

const input = JSON.parse(fs.readFileSync(".ua/tmp/ua-arch-input.json", "utf8"));
const analysis = JSON.parse(fs.readFileSync(".ua/tmp/ua-arch-results.json", "utf8"));
if (!analysis.scriptCompleted || analysis.fileStats.totalFileNodes !== input.fileNodes.length) {
  throw new Error("architecture structural analysis result does not match input");
}

const layers = [
  { id: "layer:product-inventory", name: "상품·재고 서비스", description: "product-service의 상품 catalog, category, attribute, SKU, stock 및 reservation 업무를 담당합니다.", nodeIds: [] },
  { id: "layer:payment", name: "결제 서비스", description: "payment-service의 결제 승인·취소, idempotency, outbox, 보상 및 복구 흐름을 담당합니다.", nodeIds: [] },
  { id: "layer:order", name: "주문 서비스", description: "order-service의 cart, 주문 생성·조회, 주문 항목 상태와 취소 복구 흐름을 담당합니다.", nodeIds: [] },
  { id: "layer:merchant-settlement-risk", name: "가맹점·정산·위험 서비스", description: "merchant-limit, settlement, risk-management service의 한도 정책, 정산·지급·reserve 및 위험 판정을 담당합니다.", nodeIds: [] },
  { id: "layer:identity-edge", name: "인증·Edge API", description: "user-service와 api-gateway에서 사용자 인증, JWT 검증, 외부 REST API 진입점과 downstream routing을 담당합니다.", nodeIds: [] },
  { id: "layer:frontend", name: "Frontend UI", description: "React/Vite 기반 commerce 화면, component, client-side 상태와 backend API 연동을 담당합니다.", nodeIds: [] },
  { id: "layer:shared-platform", name: "공통 Platform·Observability", description: "공통 tracing·metrics 설정, Gradle wrapper와 root build configuration 등 서비스 공통 기반을 제공합니다.", nodeIds: [] },
  { id: "layer:data", name: "데이터 Schema·Migration", description: "각 서비스가 소유한 MySQL table과 순차 Flyway migration으로 persistence schema의 변경 이력을 관리합니다.", nodeIds: [] },
  { id: "layer:infrastructure", name: "인프라·CI/CD·성능 검증", description: "Docker/Compose, Terraform, GitHub Actions, k6와 운영 script로 배포 topology 및 load test 자동화를 관리합니다.", nodeIds: [] },
  { id: "layer:documentation", name: "문서·Planning", description: "system design, 운영 guide, workstream 요구사항·계획·검증 기록과 분석 metadata를 보존합니다.", nodeIds: [] },
];
const byId = new Map(layers.map(layer => [layer.id, layer]));

function layerFor(node) {
  const top = node.filePath.includes("/") ? node.filePath.split("/")[0] : "root";
  if (node.type === "document" || [".planning", ".ua", "docs", "sysdesign", ".claude"].includes(top)) return "layer:documentation";
  if (["table", "schema", "endpoint"].includes(node.type)) return "layer:data";
  if (["service", "resource", "pipeline"].includes(node.type) || ["infra", ".github", "k6", "logs"].includes(top)) return "layer:infrastructure";
  if (top === "product-service") return "layer:product-inventory";
  if (top === "payment-service") return "layer:payment";
  if (top === "order-service") return "layer:order";
  if (["merchant-limit-service", "settlement-service", "risk-management-service"].includes(top)) return "layer:merchant-settlement-risk";
  if (["user-service", "api-gateway"].includes(top)) return "layer:identity-edge";
  if (top === "frontend") return "layer:frontend";
  if (["common-observability", "gradle", "root"].includes(top)) return "layer:shared-platform";
  throw new Error(`unassigned node: ${node.id} (${node.filePath})`);
}

for (const node of input.fileNodes) byId.get(layerFor(node)).nodeIds.push(node.id);
for (const layer of layers) layer.nodeIds.sort();
fs.writeFileSync(".ua/intermediate/layers.json", JSON.stringify(layers, null, 2) + "\n");
console.log(JSON.stringify(Object.fromEntries(layers.map(layer => [layer.name, layer.nodeIds.length]))));
