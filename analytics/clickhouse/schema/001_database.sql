-- Warehouse analitico del programma (RF-121): ClickHouse alimentato direttamente dai topic Kafka (CloudEvents JSON).
-- Nessun dato anagrafico: solo id loyalty (D12); l'id membro è mantenuto per i conteggi distinti, mai esposto nei cruscotti.
CREATE DATABASE IF NOT EXISTS loyalty ON CLUSTER '{cluster}';
