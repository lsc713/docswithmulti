# ─────────────────────────────────────────────────────────────
# k3s 스케일아웃 리그 노드 (9대)
#   k3s-server 1 + agent 3 = 클러스터. mysql×3 외부 고정. k6·obs 외부.
#   SG self_all(security.tf)이 같은 SG 인스턴스 간 전 포트 허용 → k3s 포트 규칙 불필요.
# ─────────────────────────────────────────────────────────────
data "aws_ssm_parameter" "al2023_arm" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
}

locals {
  server_ip = "10.0.1.10"
  instances = {
    k3s-server    = { type = "c7g.large", ip = local.server_ip, disk = 40, role = "server", spot = false }
    k3s-agent-1   = { type = "m7g.xlarge", ip = "10.0.1.11", disk = 60, role = "agent", spot = false }
    k3s-agent-2   = { type = "m7g.xlarge", ip = "10.0.1.12", disk = 60, role = "agent", spot = false }
    k3s-agent-3   = { type = "m7g.xlarge", ip = "10.0.1.13", disk = 60, role = "agent", spot = false }
    mysql-payment = { type = "m7g.large", ip = "10.0.1.30", disk = 100, role = "db" }
    mysql-risk    = { type = "m7g.large", ip = "10.0.1.31", disk = 100, role = "db" }
    cold-db       = { type = "c7g.large", ip = "10.0.1.32", disk = 100, role = "db" }
    k6            = { type = "c7g.xlarge", ip = "10.0.1.20", disk = 30, role = "k6" }
    obs           = { type = "t4g.medium", ip = "10.0.1.50", disk = 30, role = "obs", spot = false }
  }
}

# role별 user_data: server=k3s server(taint), agent=join, 그 외=docker(기존 compose 재사용)
locals {
  ud_server = <<-EOF
    #!/bin/bash
    set -e
    curl -sfL https://get.k3s.io | K3S_TOKEN='${var.k3s_token}' sh -s - server \
      --node-taint node-role.kubernetes.io/control-plane=true:NoSchedule \
      --tls-san ${local.server_ip} --write-kubeconfig-mode 644
  EOF
  ud_agent = <<-EOF
    #!/bin/bash
    set -e
    # server API가 뜰 때까지 대기 후 join (agent 자체 재시도도 있으나 명시적 대기)
    until curl -sk https://${local.server_ip}:6443/ping >/dev/null 2>&1; do sleep 5; done
    curl -sfL https://get.k3s.io | K3S_URL='https://${local.server_ip}:6443' K3S_TOKEN='${var.k3s_token}' sh -s - agent
  EOF
  ud_docker = <<-EOF
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

  ami                         = data.aws_ssm_parameter.al2023_arm.value
  instance_type               = each.value.type
  subnet_id                   = aws_subnet.private.id
  private_ip                  = each.value.ip
  vpc_security_group_ids      = [aws_security_group.internal.id]
  iam_instance_profile        = aws_iam_instance_profile.ssm.name
  associate_public_ip_address = false

  user_data = each.value.role == "server" ? local.ud_server : (
    each.value.role == "agent" ? local.ud_agent : local.ud_docker
  )

  root_block_device {
    volume_type = "gp3"
    volume_size = each.value.disk
  }

  dynamic "instance_market_options" {
    for_each = (var.use_spot && lookup(each.value, "spot", true)) ? [1] : []
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
