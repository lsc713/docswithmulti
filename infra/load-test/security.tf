# ─────────────────────────────────────────────────────────────
# 보안: 인터넷 인바운드 0 · 인스턴스 간 사설 IP 통신만 허용
#       접속은 SSM Session Manager (22 미개방, 키페어 불필요)
# ─────────────────────────────────────────────────────────────

resource "aws_security_group" "internal" {
  name        = "${var.project}-internal"
  description = "internal private-IP traffic only, no internet inbound"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${var.project}-internal" }
}

# 같은 SG 소속 인스턴스끼리는 모든 포트 허용 (테스트 환경)
resource "aws_vpc_security_group_ingress_rule" "self_all" {
  security_group_id            = aws_security_group.internal.id
  referenced_security_group_id = aws_security_group.internal.id
  ip_protocol                  = "-1"
  description                  = "all ports between instances (private IP)"
}

# 아웃바운드 전체 허용 (NAT 경유 이미지 pull + SSM)
resource "aws_vpc_security_group_egress_rule" "all_out" {
  security_group_id = aws_security_group.internal.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
  description       = "all egress (via NAT)"
}

# product 프로필의 NAT 인스턴스: private subnet에서 전달된 패킷만 받는다.
resource "aws_security_group" "nat" {
  name        = "${var.project}-nat-instance"
  description = "NAT instance forwarding from private subnet"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${var.project}-nat-instance" }
}

resource "aws_vpc_security_group_ingress_rule" "nat_private" {
  security_group_id = aws_security_group.nat.id
  cidr_ipv4         = var.private_subnet_cidr
  ip_protocol       = "-1"
  description       = "forward private subnet traffic"
}

resource "aws_vpc_security_group_egress_rule" "nat_all_out" {
  security_group_id = aws_security_group.nat.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
  description       = "NAT instance internet egress"
}

# ── SSM 접속용 IAM (SSH 키 불필요) ──
data "aws_iam_policy_document" "ec2_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "ssm" {
  name               = "${var.project}-ssm-role"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json
}

resource "aws_iam_role_policy_attachment" "ssm_core" {
  role       = aws_iam_role.ssm.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# 컨테이너 로그를 CloudWatch Logs 로 보내기 위한 최소 권한 (awslogs 드라이버).
# → `docker logs`+grep 대신 콘솔 Logs Insights 에서 GUI 로 검색/집계.
resource "aws_iam_role_policy" "cw_logs" {
  name = "${var.project}-cw-logs"
  role = aws_iam_role.ssm.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "logs:CreateLogGroup",
        "logs:CreateLogStream",
        "logs:PutLogEvents",
        "logs:DescribeLogStreams",
        "logs:DescribeLogGroups"
      ]
      Resource = "arn:aws:logs:*:*:log-group:/loadtest/*"
    }]
  })
}

resource "aws_iam_instance_profile" "ssm" {
  name = "${var.project}-ssm-profile"
  role = aws_iam_role.ssm.name
}
