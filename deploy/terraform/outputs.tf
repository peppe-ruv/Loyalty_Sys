output "cluster_name" { value = module.eks.cluster_name }
output "cluster_endpoint" { value = module.eks.cluster_endpoint }
output "kubeconfig_command" { value = "aws eks update-kubeconfig --region ${var.region} --name ${module.eks.cluster_name}" }
output "db_address" { value = module.rds.db_instance_address }
output "kafka_bootstrap_iam" { value = aws_msk_cluster.this.bootstrap_brokers_sasl_iam }
output "redis_endpoint" { value = aws_elasticache_replication_group.this.primary_endpoint_address }
output "assets_bucket" { value = aws_s3_bucket.assets.bucket }
output "secret_db_name" { value = aws_secretsmanager_secret.db.name }
output "helm_values_hint" {
  value = "helm upgrade --install loyalty-hub deploy/helm/loyalty-hub -n loyalty --create-namespace -f deploy/helm/loyalty-hub/values-${var.environment}.yaml --set global.kafka.bootstrap=${aws_msk_cluster.this.bootstrap_brokers_sasl_iam} --set global.redis.host=${aws_elasticache_replication_group.this.primary_endpoint_address}"
}
