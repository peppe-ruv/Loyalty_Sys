environment         = "prod"
node_instance_types = ["m7i.xlarge"]
node_min            = 3
node_max            = 24
db_instance_class   = "db.r7g.2xlarge"
db_multi_az         = true
kafka_broker_type   = "kafka.m7g.large"
kafka_brokers       = 3
redis_node_type     = "cache.r7g.large"
# Seconda region per DR (D11): eu-central-1 solo se Legal ammette copie dei dati concorso fuori Italia (RC-01).
dr_region = ""
