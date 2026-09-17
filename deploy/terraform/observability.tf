# RF-117..RF-120: storage a lungo termine per metriche (Thanos), log (Loki) e tracce (Tempo), con accesso IRSA senza chiavi.
locals {
  observability_buckets = { thanos = "thanos", loki = "loki", tempo = "tempo", clickhouse = "clickhouse-backup" }
}

resource "aws_s3_bucket" "observability" {
  for_each = local.observability_buckets
  bucket   = "${local.name}-${each.value}-${data.aws_caller_identity.current.account_id}"
  tags     = { component = "observability" }
}

resource "aws_s3_bucket_public_access_block" "observability" {
  for_each                = aws_s3_bucket.observability
  bucket                  = each.value.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "observability" {
  for_each = aws_s3_bucket.observability
  bucket   = each.value.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "aws:kms"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "observability" {
  for_each = aws_s3_bucket.observability
  bucket   = each.value.id
  rule {
    id     = "expire"
    status = "Enabled"
    filter {}
    expiration {
      days = each.key == "thanos" ? 400 : each.key == "loki" ? 45 : each.key == "tempo" ? 21 : 35
    }
    noncurrent_version_expiration {
      noncurrent_days = 7
    }
  }
}

# Ruolo IRSA condiviso dai componenti dello stack (Thanos sidecar/store/compactor, Loki, Tempo, ClickHouse backup)
module "observability_irsa" {
  source  = "terraform-aws-modules/iam/aws//modules/iam-role-for-service-accounts-eks"
  version = "~> 5.44"

  role_name = "${local.name}-observability"
  oidc_providers = {
    main = {
      provider_arn               = module.eks.oidc_provider_arn
      namespace_service_accounts = ["observability:*", "analytics:*"]
    }
  }
  role_policy_arns = { s3 = aws_iam_policy.observability_s3.arn }
}

resource "aws_iam_policy" "observability_s3" {
  name = "${local.name}-observability-s3"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["s3:ListBucket", "s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:AbortMultipartUpload", "s3:ListMultipartUploadParts"]
      Resource = flatten([for b in aws_s3_bucket.observability : [b.arn, "${b.arn}/*"]])
    }]
  })
}

# Database Grafana e Superset sulla stessa istanza RDS (schemi separati): niente sqlite, HA vera.
resource "aws_secretsmanager_secret" "observability_db" {
  name = "${local.name}/observability-db"
}

resource "aws_secretsmanager_secret_version" "observability_db" {
  secret_id = aws_secretsmanager_secret.observability_db.id
  secret_string = jsonencode({
    GF_DATABASE_HOST     = module.rds.db_instance_address
    GF_DATABASE_USER     = "grafana"
    GF_DATABASE_PASSWORD = random_password.grafana_db.result
    SUPERSET_DB_HOST     = module.rds.db_instance_address
    SUPERSET_DB_USER     = "superset"
    SUPERSET_DB_PASSWORD = random_password.superset_db.result
    SUPERSET_SECRET_KEY  = random_password.superset_secret.result
  })
}

resource "random_password" "grafana_db" {
  length  = 32
  special = false
}

resource "random_password" "superset_db" {
  length  = 32
  special = false
}

resource "random_password" "superset_secret" {
  length  = 48
  special = false
}

output "observability_role_arn" {
  value = module.observability_irsa.iam_role_arn
}

output "observability_buckets" {
  value = { for k, b in aws_s3_bucket.observability : k => b.bucket }
}
