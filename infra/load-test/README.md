# 부하 실측 인프라 (Terraform)

결제 취소 시스템 부하 실측용 AWS 인프라. **사설 IP 전용 · 단일 AZ · Graviton Spot · SSM 접속.**

- 구성 근거: [`../../docs/load-test/measurement-journey.md`](../../docs/load-test/measurement-journey.md)
- 구성도: [`../../docs/load-test/topology.html`](../../docs/load-test/topology.html)

## 프로필

- `full` (기본): 기존 결제 취소 실측 11대 + NAT Gateway.
- `product`: 상품 상세 전용 private 3대(`k6`, `product`, `mysql-product`) + public `t4g.nano` NAT 인스턴스. 모두 같은 AZ이며 테스트 트래픽은 사설 IP만 사용한다.

## full 프로필 (11대)

| role | 인스턴스 | 사설 IP | 역할 |
|------|---------|---------|------|
| `k6` | c7g.xlarge | 10.0.1.10 | 부하생성기 (분리 필수) |
| `payment` | c7g.xlarge | 10.0.1.20 | 핫패스 주인공 |
| `risk` | c7g.xlarge | 10.0.1.21 | 한도 차감 동시성 |
| `cold-svc` | c7g.large | 10.0.1.22 | merchant-limit + order (합침) |
| `product` | c7g.xlarge | 10.0.1.23 | 상품 상세 조회 |
| `mysql-payment` | m7g.large | 10.0.1.30 | TX3 row lock 대상 |
| `mysql-risk` | m7g.large | 10.0.1.31 | 한도 소진 경합 |
| `cold-db` | c7g.large | 10.0.1.32 | mysql-merchant + mysql-order |
| `mysql-product` | m7g.large | 10.0.1.33 | product_db |
| `infra` | m7g.large | 10.0.1.40 | Redis + Kafka(1-broker) |
| `obs` | t4g.medium | 10.0.1.50 | Prometheus + Grafana |

## 사전 준비

- AWS CLI 인증 (`aws sts get-caller-identity` 확인)
- [Session Manager 플러그인](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-working-with-install-plugin.html) 설치
- Terraform >= 1.5

## 실행

```bash
cd infra/load-test
cp terraform.tfvars.example terraform.tfvars   # 필요 시 값 조정
terraform init
terraform apply
```

상품 상세만 측정할 때는 다음 예제를 사용한다. `terraform apply`부터 과금된다.

```bash
cd infra/load-test
cp product-only.tfvars.example terraform.tfvars
terraform init
terraform apply
```

apply 후 접속 명령과 사설 IP가 출력된다:

```bash
terraform output ssm_connect     # role => aws ssm start-session --target i-xxxx
terraform output private_ips     # role => 10.0.1.xx
```

## 접속 (SSH 없이 SSM)

```bash
aws ssm start-session --target $(terraform output -json instance_ids | jq -r .payment)
```

각 인스턴스엔 docker + docker compose 플러그인이 부트스트랩 되어 있다.
role별로 해당 컨테이너만 올린다 (payment 인스턴스엔 payment-service만, mysql-payment 인스턴스엔 그 DB만).
서비스 간 주소는 위 **고정 사설 IP**로 배선한다.

## ⚠️ 비용 주의

- `full`의 **NAT Gateway가 주요 유휴 비용원**이다. `product`는 `t4g.nano` NAT 인스턴스를 사용한다.
- 테스트 **세션이 끝나면 반드시 정리**:

```bash
terraform destroy
```

- `use_spot=true`를 유지하고 테스트하지 않을 때는 리소스를 남기지 않는다.

## 실측 절차

전체 수행/기록 절차는 [`measurement-journey.md` §7 실행 절차(Runbook)](../../docs/load-test/measurement-journey.md) 참조.
**부하 테스트 결과는 매번 그 문서 §8 실행 로그에 append한다.**
