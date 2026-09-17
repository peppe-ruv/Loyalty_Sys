# Disaster recovery (ADR-011, opzione A): backup continui e replica asincrona in seconda region.
# Attivo solo se dr_region è valorizzata: per l'instant win il mirroring fuori Italia non è ammesso (RC-01),
# quindi in attesa del parere Legal la copia resta nella stessa region (RDS Multi-AZ + snapshot).
provider "aws" {
  alias  = "dr"
  region = var.dr_region != "" ? var.dr_region : var.region
}

resource "aws_backup_vault" "primary" {
  name = "${local.name}-vault"
}

resource "aws_backup_plan" "this" {
  name = "${local.name}-plan"
  rule {
    rule_name                = "continuous"
    target_vault_name        = aws_backup_vault.primary.name
    schedule                 = "cron(0 */1 * * ? *)"
    enable_continuous_backup = true
    lifecycle { delete_after = 35 }
    dynamic "copy_action" {
      for_each = var.dr_region != "" ? [1] : []
      content {
        destination_vault_arn = aws_backup_vault.dr[0].arn
        lifecycle { delete_after = 35 }
      }
    }
  }
}

resource "aws_backup_vault" "dr" {
  count    = var.dr_region != "" ? 1 : 0
  provider = aws.dr
  name     = "${local.name}-dr-vault"
}

resource "aws_backup_selection" "rds" {
  iam_role_arn = aws_iam_role.backup.arn
  name         = "${local.name}-rds"
  plan_id      = aws_backup_plan.this.id
  resources    = [module.rds.db_instance_arn]
}

resource "aws_iam_role" "backup" {
  name = "${local.name}-backup"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "backup.amazonaws.com" }, Action = "sts:AssumeRole" }]
  })
}
resource "aws_iam_role_policy_attachment" "backup" {
  role       = aws_iam_role.backup.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSBackupServiceRolePolicyForBackup"
}
