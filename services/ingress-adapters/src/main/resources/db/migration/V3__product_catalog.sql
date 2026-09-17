-- RF-101 catalogo prodotti in sola lettura (copia da CSV).
CREATE TABLE ingressadapters.product (
    sku        VARCHAR(64)  PRIMARY KEY,
    name       VARCHAR(240),
    category   VARCHAR(120),
    brand      VARCHAR(120),
    price      NUMERIC(12,2),
    labels     TEXT,
    attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
    active     BOOLEAN NOT NULL DEFAULT true,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX product_category_idx ON ingressadapters.product (category);
CREATE INDEX product_brand_idx ON ingressadapters.product (brand);
