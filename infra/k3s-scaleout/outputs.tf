output "private_ips" {
  description = "role => 사설 IP (config/compose 배선용)"
  value       = { for k, i in aws_instance.node : k => i.private_ip }
}

output "instance_ids" {
  description = "role => 인스턴스 ID"
  value       = { for k, i in aws_instance.node : k => i.id }
}

output "ssm_connect" {
  description = "role => SSM 접속 명령 (SSH 불필요, 22 미개방)"
  value       = { for k, i in aws_instance.node : k => "aws ssm start-session --target ${i.id}" }
}

output "region" {
  value = var.region
}

output "kubeconfig_hint" {
  description = "server 노드에서 kubectl 쓰는 법"
  value       = "aws ssm start-session --target <server-id> 후: sudo k3s kubectl get nodes"
}
