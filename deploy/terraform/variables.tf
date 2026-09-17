variable "region" {
  description = "Region AWS. Milano: i dati concorso devono restare in Italia (RC-01)."
  type        = string
  default     = "eu-south-1"
}
variable "environment" {
  type    = string
  default = "dev"
}
variable "name" {
  type    = string
  default = "loyalty-hub"
}
variable "vpc_cidr" {
  type    = string
  default = "10.40.0.0/16"
}
variable "kubernetes_version" {
  type    = string
  default = "1.33"
}
variable "node_instance_types" {
  type    = list(string)
  default = ["m7i.large"]
}
variable "node_min" {
  type    = number
  default = 3
}
variable "node_max" {
  type    = number
  default = 12
}
variable "db_instance_class" {
  type    = string
  default = "db.r7g.large"
}
variable "db_multi_az" {
  type    = bool
  default = true
}
variable "kafka_broker_type" {
  type    = string
  default = "kafka.m7g.large"
}
variable "kafka_brokers" {
  description = "Numero broker MSK: multiplo del numero di zone (3 in eu-south-1)"
  type        = number
  default     = 3
}
variable "redis_node_type" {
  type    = string
  default = "cache.r7g.large"
}
variable "dr_region" {
  description = "Region per backup e replica DR (ADR-011). Se Legal vieta copie fuori Italia lasciare vuoto (RC-01)."
  type        = string
  default     = ""
}
variable "image_registry" {
  type    = string
  default = "ghcr.io/loyalty-hub"
}
variable "image_tag" {
  type    = string
  default = "0.1.0"
}
