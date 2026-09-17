-- Tabelle sorgente sul motore Kafka: una per topic, formato JSONAsString (l'evento CloudEvents intero in una colonna).
CREATE TABLE IF NOT EXISTS loyalty.kafka_actions ON CLUSTER '{cluster}' (raw String) ENGINE = Kafka
SETTINGS kafka_broker_list = '{kafka_bootstrap}', kafka_topic_list = 'loyalty.actions.v1', kafka_group_name = 'clickhouse-actions', kafka_format = 'JSONAsString', kafka_num_consumers = 2, kafka_thread_per_consumer = 1;
CREATE TABLE IF NOT EXISTS loyalty.kafka_movements ON CLUSTER '{cluster}' (raw String) ENGINE = Kafka
SETTINGS kafka_broker_list = '{kafka_bootstrap}', kafka_topic_list = 'loyalty.movements.v1', kafka_group_name = 'clickhouse-movements', kafka_format = 'JSONAsString', kafka_num_consumers = 2;
CREATE TABLE IF NOT EXISTS loyalty.kafka_redemptions ON CLUSTER '{cluster}' (raw String) ENGINE = Kafka
SETTINGS kafka_broker_list = '{kafka_bootstrap}', kafka_topic_list = 'loyalty.redemptions.v1', kafka_group_name = 'clickhouse-redemptions', kafka_format = 'JSONAsString';
CREATE TABLE IF NOT EXISTS loyalty.kafka_contests ON CLUSTER '{cluster}' (raw String) ENGINE = Kafka
SETTINGS kafka_broker_list = '{kafka_bootstrap}', kafka_topic_list = 'loyalty.contests.v1', kafka_group_name = 'clickhouse-contests', kafka_format = 'JSONAsString';
CREATE TABLE IF NOT EXISTS loyalty.kafka_tiers ON CLUSTER '{cluster}' (raw String) ENGINE = Kafka
SETTINGS kafka_broker_list = '{kafka_bootstrap}', kafka_topic_list = 'loyalty.tiers.v1', kafka_group_name = 'clickhouse-tiers', kafka_format = 'JSONAsString';
CREATE TABLE IF NOT EXISTS loyalty.kafka_members ON CLUSTER '{cluster}' (raw String) ENGINE = Kafka
SETTINGS kafka_broker_list = '{kafka_bootstrap}', kafka_topic_list = 'loyalty.members.v1', kafka_group_name = 'clickhouse-members', kafka_format = 'JSONAsString';
CREATE TABLE IF NOT EXISTS loyalty.kafka_segments ON CLUSTER '{cluster}' (raw String) ENGINE = Kafka
SETTINGS kafka_broker_list = '{kafka_bootstrap}', kafka_topic_list = 'loyalty.segments.v1', kafka_group_name = 'clickhouse-segments', kafka_format = 'JSONAsString';
