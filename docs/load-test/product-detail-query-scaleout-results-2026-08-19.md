# MySQL 수직 스케일아웃 측정 결과

## 결론

`m7g.large`에서 `m7g.xlarge`로 확장했지만, 이번 단일 측정에서는 처리량 개선이 확인되지 않았다. baseline은 **1,143.75 → 1,025.00 RPS(-10.39%)**, ramp는 **1,190.85 → 1,134.96 RPS(-4.69%)**였다.

따라서 이번 결과만으로 수직 확장을 운영 반영하지 않는다. ramp p99는 125.53ms에서 111.13ms로 개선됐지만, baseline·ramp 모두 처리량이 낮아져 캐시 상태, 인스턴스 기동 직후 상태, 측정 변동성을 배제할 수 없다.

## 조건

- 동일 AZ: `ap-northeast-2a`
- Product: `c7g.xlarge`
- MySQL: `m7g.xlarge` (기존 기준선은 `m7g.large`)
- 데이터: 100,000 상품, 900,000 SKU
- 경로: 사설 IP 직결, API Gateway 미경유
- 분포: `realistic`
- 실패율: 0%

## 결과 비교

| 구간 | 기준선 `m7g.large` | 확장 `m7g.xlarge` | 변화 |
|---|---:|---:|---:|
| Baseline RPS | 1,143.75 | 1,025.00 | -10.39% |
| Baseline p50 | 8.23ms | 9.09ms | +10.45% |
| Baseline p95 | 11.45ms | 12.36ms | +7.95% |
| Baseline p99 | 18.44ms | 19.74ms | +7.05% |
| Ramp RPS | 1,190.85 | 1,134.96 | -4.69% |
| Ramp p50 | 21.26ms | 24.63ms | +15.87% |
| Ramp p95 | 78.10ms | 80.71ms | +3.34% |
| Ramp p99 | 125.53ms | 111.13ms | -11.47% |
| HTTP 실패율 | 0% | 0% | 동일 |

확장 측정 산출물:

- `k6/results/20260819T141740Z-smoke-realistic-6391.summary.json`
- `k6/results/20260819T141808Z-baseline-realistic-6470.summary.json`
- `k6/results/20260819T142123Z-ramp-realistic-6969.summary.json`

## 해석과 한계

이 실험은 각 조건을 한 번씩만 실행했고, 확장 스택의 Prometheus CPU 시계열은 SSM 터널 세션 종료로 보존하지 못했다. 따라서 `m7g.xlarge`가 실제로 CPU 병목을 해소했는지 판정할 수 없다. RPS가 감소한 것은 확장의 실패 증거라기보다 반복 측정·워밍업·스팟/네트워크 변동을 통제하지 못한 결과다.

## 다음 검증

1. 각 인스턴스 타입을 최소 3회씩 교차 실행한다.
2. 매 회차에 3분 워밍업 후 baseline 3분, ramp 9분을 실행한다.
3. MySQL CPU 평균·최대, buffer pool reads, 디스크 I/O, Hikari pending을 Prometheus에 파일로 보존한다.
4. `m7g.xlarge`에서 RPS가 일관되게 10% 이상 증가하고 MySQL CPU가 낮아질 때만 수직 확장을 채택한다.

## 자원 정리

측정 종료 후 Terraform 23개 자원을 삭제했고, Product·MySQL·k6·NAT EC2를 종료했다.
