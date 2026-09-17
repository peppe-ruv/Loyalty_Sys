-- RF-100 collezioni di valori riutilizzabili nelle condizioni.
CREATE TABLE segmentservice.collection_value (
    collection_id VARCHAR(64)  NOT NULL,
    value         VARCHAR(255) NOT NULL,
    PRIMARY KEY (collection_id, value)
);
