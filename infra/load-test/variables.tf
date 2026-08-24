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

variable "load_test_profile" {
  description = "full=전체 취소 리그, product=상품 상세 단일 노드, product-scaleout=공용 Redis 이중 노드, product-replica=상품 읽기 복제본"
  type        = string
  default     = "full"

  validation {
    condition     = contains(["full", "product", "product-scaleout", "product-replica"], var.load_test_profile)
    error_message = "load_test_profile must be full, product, product-scaleout, or product-replica."
  }
}

variable "vpc_cidr" {
  type    = string
  default = "10.0.0.0/16"
}

variable "public_subnet_cidr" {
  description = "NAT Gateway 또는 NAT 인스턴스용 퍼블릭 서브넷"
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

variable "mysql_gp3_iops" {
  description = "product MySQL gp3 provisioned IOPS (null이면 AWS 기본 3000)"
  type        = number
  default     = null

  validation {
    condition     = var.mysql_gp3_iops == null || (var.mysql_gp3_iops >= 3000 && var.mysql_gp3_iops <= 80000)
    error_message = "mysql_gp3_iops must be null or between 3000 and 80000."
  }
}

variable "mysql_gp3_throughput" {
  description = "product MySQL gp3 throughput MiB/s (null이면 AWS 기본 125)"
  type        = number
  default     = null

  validation {
    condition     = var.mysql_gp3_throughput == null || (var.mysql_gp3_throughput >= 125 && var.mysql_gp3_throughput <= 2000)
    error_message = "mysql_gp3_throughput must be null or between 125 and 2000."
  }
}
