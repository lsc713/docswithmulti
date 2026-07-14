variable "region" {
  description = "AWS 리전 (KST 도메인이라 서울 기본값)"
  type        = string
  default     = "ap-northeast-2"
}

variable "az" {
  description = "단일 AZ (baseline은 same-AZ로 네트워크 노이즈 최소화)"
  type        = string
  default     = "ap-northeast-2a"
}

variable "project" {
  description = "리소스 태그 접두사"
  type        = string
  default     = "cancel-loadtest"
}

variable "vpc_cidr" {
  type    = string
  default = "10.0.0.0/16"
}

variable "public_subnet_cidr" {
  description = "NAT Gateway 전용 퍼블릭 서브넷"
  type        = string
  default     = "10.0.0.0/24"
}

variable "private_subnet_cidr" {
  description = "모든 SUT/부하생성기가 사설 IP로 사는 프라이빗 서브넷"
  type        = string
  default     = "10.0.1.0/24"
}

variable "use_spot" {
  description = "Spot 인스턴스 사용 (온디맨드 대비 ~70% 절감). 부하 데이터는 버려도 되므로 DB도 Spot 허용"
  type        = bool
  default     = true
}

variable "k3s_token" {
  description = "k3s server/agent 공유 조인 토큰 (terraform.tfvars에 설정, 커밋 금지)"
  type        = string
  sensitive   = true
}
