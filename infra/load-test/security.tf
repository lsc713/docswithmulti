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

resource "aws_iam_instance_profile" "ssm" {
  name = "${var.project}-ssm-profile"
  role = aws_iam_role.ssm.name
}
