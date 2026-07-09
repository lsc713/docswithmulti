# ─────────────────────────────────────────────────────────────
# 인스턴스 (9대) — 핫패스는 단독, 콜드패스는 합침
#   근거: docs/load-test/measurement-journey.md §0·§9(패밀리 규칙), topology.html
#   전부 Graviton(ARM) + Spot + gp3 + 사설 IP 고정
#
#   사이징 원칙: 핫패스(payment/risk)가 병목이 돼야 실측이 의미 있다.
#   → 부하생성기(k6)·DB는 병목이 되면 안 되되, 과하면 병목을 "덮어" 측정을 흐린다.
#   패밀리: c7g=compute(2GB/vCPU) · m7g=범용(4GB) · r7g=메모리(8GB) · t4g=버스터블
# ─────────────────────────────────────────────────────────────

# Amazon Linux 2023 (arm64) 최신 AMI
data "aws_ssm_parameter" "al2023_arm" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
}

locals {
  # role => { type, ip, disk(GB) }
  #   ip : private_subnet_cidr(10.0.1.0/24) 내 고정 사설 IP → config/compose 배선 편하게
  instances = {
    k6            = { type = "c7g.xlarge", ip = "10.0.1.10", disk = 30 } # 부하생성기 (분리 필수)
    payment       = { type = "c7g.xlarge", ip = "10.0.1.20", disk = 30 } # 핫패스 주인공
    risk          = { type = "c7g.xlarge", ip = "10.0.1.21", disk = 30 } # 한도 차감 동시성
    cold-svc      = { type = "c7g.large", ip = "10.0.1.22", disk = 30 }  # merchant-limit + order (합침)
    mysql-payment = { type = "m7g.large", ip = "10.0.1.30", disk = 100 } # TX3 row lock 대상 (r7g 16GB→8GB: 버퍼풀이 I/O 병목 덮는 것 방지)
    mysql-risk    = { type = "m7g.large", ip = "10.0.1.31", disk = 100 } # 한도 소진 경합 (동상)
    cold-db       = { type = "c7g.large", ip = "10.0.1.32", disk = 100 } # mysql-merchant + mysql-order (콜드, 4GB로 충분)
    infra         = { type = "m7g.large", ip = "10.0.1.40", disk = 50 }  # Redis + Kafka(1-broker), page cache용 RAM 유지
    obs           = { type = "t4g.medium", ip = "10.0.1.50", disk = 30 } # Prometheus + Grafana (2GB→4GB: 9타깃 히스토그램 스크레이프 안정화)
  }
}

# 도커 + compose 부트스트랩 (배포는 SSM 접속 후 role별로 수행)
locals {
  user_data = <<-EOF
    #!/bin/bash
    set -e
    dnf install -y docker git
    systemctl enable --now docker
    usermod -aG docker ec2-user
    mkdir -p /usr/local/lib/docker/cli-plugins
    curl -sSL "https://github.com/docker/compose/releases/latest/download/docker-compose-linux-aarch64" \
      -o /usr/local/lib/docker/cli-plugins/docker-compose
    chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
  EOF
}

resource "aws_instance" "node" {
  for_each = local.instances

  ami                    = data.aws_ssm_parameter.al2023_arm.value
  instance_type          = each.value.type
  subnet_id              = aws_subnet.private.id
  private_ip             = each.value.ip
  vpc_security_group_ids = [aws_security_group.internal.id]
  iam_instance_profile   = aws_iam_instance_profile.ssm.name
  user_data              = local.user_data

  # 사설 IP만 — 퍼블릭 IP 없음
  associate_public_ip_address = false

  root_block_device {
    volume_type = "gp3"
    volume_size = each.value.disk
    # gp3 기본 3000 IOPS / 125 MBps 무료. DB I/O 병목 시 iops/throughput 상향.
  }

  dynamic "instance_market_options" {
    for_each = var.use_spot ? [1] : []
    content {
      market_type = "spot"
      spot_options {
        spot_instance_type             = "one-time"
        instance_interruption_behavior = "terminate"
      }
    }
  }

  tags = {
    Name = "${var.project}-${each.key}"
    Role = each.key
  }
}
