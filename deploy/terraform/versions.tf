terraform {
  required_version = ">= 1.9"
  required_providers {
    aws        = { source = "hashicorp/aws", version = "~> 6.0" }
    kubernetes = { source = "hashicorp/kubernetes", version = "~> 2.38" }
    helm       = { source = "hashicorp/helm", version = "~> 3.0" }
    random     = { source = "hashicorp/random", version = "~> 3.7" }
  }
  # Stato remoto: creare bucket e tabella una volta (make bootstrap-state), poi decommentare.
  # backend "s3" {
  #   bucket         = "loyalty-hub-tfstate"
  #   key            = "eks/terraform.tfstate"
  #   region         = "eu-south-1"
  #   dynamodb_table = "loyalty-hub-tflock"
  #   encrypt        = true
  # }
}

provider "aws" {
  region = var.region
  default_tags { tags = local.tags }
}

data "aws_eks_cluster_auth" "this" { name = module.eks.cluster_name }

provider "kubernetes" {
  host                   = module.eks.cluster_endpoint
  cluster_ca_certificate = base64decode(module.eks.cluster_certificate_authority_data)
  token                  = data.aws_eks_cluster_auth.this.token
}

provider "helm" {
  kubernetes = {
    host                   = module.eks.cluster_endpoint
    cluster_ca_certificate = base64decode(module.eks.cluster_certificate_authority_data)
    token                  = data.aws_eks_cluster_auth.this.token
  }
}
