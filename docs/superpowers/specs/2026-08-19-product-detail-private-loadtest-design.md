# 상품 상세 사설망 최소 비용 부하 테스트 설계

## 목표

상품 상세 `GET /v1/products/{id}`의 첫 기준선과 병목 지점을 AWS에서 확인한다. 전체 결제 인프라는 띄우지 않고, 같은 AZ의 사설 IP로만 테스트 트래픽을 흘린다.

## 구성

- public subnet: 소형 NAT 인스턴스 1대. private 노드의 이미지 pull과 SSM 통신만 중계한다.
- private subnet: k6/Prometheus, product-service, MySQL 각 1대.
- 모든 인스턴스는 같은 AZ에 두고, 테스트 노드는 Spot을 사용한다.
- k6 → product-service → MySQL 트래픽은 고정 사설 IP만 사용한다.
- Prometheus는 k6 호스트에 함께 두고 product/JVM, 노드, MySQL 지표만 수집한다.

## 제외 범위

- API Gateway: 서비스 자체 기준선이므로 제외한다.
- Kafka: 상품 상세 조회 경로와 무관하므로 listener를 비활성화한다.
- MinIO: 상세 응답은 S3 presigned URL을 로컬에서 만들 뿐 객체 저장소를 호출하지 않으므로 제외한다.
- 별도 Grafana/Tempo 관측 노드: 첫 병목 탐색에는 Prometheus와 k6 결과로 충분하다.
- Redis 전용 노드: 스케줄러 락 빈을 만족시키는 소형 Redis를 product 호스트에 함께 둔다.

## 실행과 판정

1. 1,000개 상품 데이터를 적재하고 smoke 요청으로 응답 구조를 검증한다.
2. 10 VU 기준선을 실행한 뒤 50, 100, 200, 400 VU로 단계 상승한다.
3. 각 단계에서 처리량, p95/p99, 오류율과 product CPU/JVM/DB pool, MySQL CPU/connection/slow query를 함께 기록한다.
4. 처리량 증가가 둔화되면서 지연 또는 오류가 급증한 최초 단계를 포화점으로 본다.
5. k6 호스트가 먼저 포화되면 결과를 폐기하고 생성기만 상향한다.

## 비용 및 정리

- 기존 NAT Gateway 대신 소형 NAT 인스턴스를 사용한다.
- 테스트 종료 즉시 `terraform destroy`한다.
- NAT 장애는 외부 다운로드와 SSM에만 영향을 주며, 이미 기동된 사설망 부하 경로에는 영향을 주지 않는다.
