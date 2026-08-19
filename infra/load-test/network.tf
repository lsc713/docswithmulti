# ─────────────────────────────────────────────────────────────
# VPC · 서브넷 · 라우팅
#   private subnet : 모든 인스턴스 (사설 IP만, 퍼블릭 IP 없음)
#   public subnet  : NAT Gateway 전용 (인스턴스 없음)
#   egress         : private → NAT GW → IGW (도커 이미지 pull + SSM)
# ─────────────────────────────────────────────────────────────

resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = "${var.project}-vpc" }
}

resource "aws_internet_gateway" "igw" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${var.project}-igw" }
}

resource "aws_subnet" "public" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = var.public_subnet_cidr
  availability_zone = var.az
  tags              = { Name = "${var.project}-public" }
}

resource "aws_subnet" "private" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = var.private_subnet_cidr
  availability_zone       = var.az
  map_public_ip_on_launch = false # 사설 IP만
  tags                    = { Name = "${var.project}-private" }
}

# full은 NAT Gateway, product는 소형 NAT 인스턴스를 인터넷 출구로 사용한다.
resource "aws_eip" "nat" {
  count  = var.load_test_profile == "full" ? 1 : 0
  domain = "vpc"
  tags   = { Name = "${var.project}-nat-eip" }
}

resource "aws_nat_gateway" "nat" {
  count         = var.load_test_profile == "full" ? 1 : 0
  allocation_id = aws_eip.nat[0].id
  subnet_id     = aws_subnet.public.id
  tags          = { Name = "${var.project}-nat" }
  depends_on    = [aws_internet_gateway.igw]
}

resource "aws_instance" "nat" {
  count                       = var.load_test_profile == "product" ? 1 : 0
  ami                         = data.aws_ssm_parameter.al2023_arm.value
  instance_type               = "t4g.nano"
  subnet_id                   = aws_subnet.public.id
  vpc_security_group_ids      = [aws_security_group.nat.id]
  associate_public_ip_address = true
  source_dest_check           = false

  user_data = <<-EOF
    #!/bin/bash
    set -e
    dnf install -y iptables-services
    echo 'net.ipv4.ip_forward = 1' >/etc/sysctl.d/99-nat.conf
    sysctl --system
    outbound_if=$(ip -o route get 1.1.1.1 | awk '{print $5; exit}')
    iptables -t nat -A POSTROUTING -o "$outbound_if" -j MASQUERADE
    iptables -P FORWARD ACCEPT
    service iptables save
    systemctl enable --now iptables
  EOF

  root_block_device {
    volume_type = "gp3"
    volume_size = 8
  }

  tags = {
    Name = "${var.project}-nat-instance"
    Role = "nat"
  }

  depends_on = [aws_route_table_association.public]
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.igw.id
  }

  tags = { Name = "${var.project}-public-rt" }
}

resource "aws_route_table" "private" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${var.project}-private-rt" }
}

resource "aws_route" "private_nat_gateway" {
  count                  = var.load_test_profile == "full" ? 1 : 0
  route_table_id         = aws_route_table.private.id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.nat[0].id
}

resource "aws_route" "private_nat_instance" {
  count                  = var.load_test_profile == "product" ? 1 : 0
  route_table_id         = aws_route_table.private.id
  destination_cidr_block = "0.0.0.0/0"
  network_interface_id   = aws_instance.nat[0].primary_network_interface_id
}

resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table_association" "private" {
  subnet_id      = aws_subnet.private.id
  route_table_id = aws_route_table.private.id
}
