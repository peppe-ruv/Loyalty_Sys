#!/usr/bin/env sh
# Bootstrap idempotente di Superset (RF-122): admin, database ClickHouse, ruolo guest con RLS, import dei cruscotti.
set -e
superset db upgrade
superset fab create-admin --username "${SUPERSET_ADMIN:-admin}" --firstname Admin --lastname Loyalty --email admin@loyalty.local --password "${SUPERSET_ADMIN_PASSWORD:-admin}" 2>/dev/null || true
superset init
superset set-database-uri -d "Loyalty warehouse" -u "clickhousedb://${CLICKHOUSE_USER:-superset}:${CLICKHOUSE_PASSWORD:-superset}@${CLICKHOUSE_HOST:-clickhouse}:8123/loyalty" || true
python -c "import shutil; shutil.make_archive('/tmp/loyalty-kpi', 'zip', '/app/dashboards', 'loyalty-kpi')"
superset import-dashboards -p /tmp/loyalty-kpi.zip -u "${SUPERSET_ADMIN:-admin}" || true
# ruolo per i guest token: sola lettura sui dataset loyalty.*; RLS per canale quando l'operatore è di un solo canale
python - <<'PY'
from superset.app import create_app
app = create_app()
with app.app_context():
    from superset import security_manager as sm, db
    role = sm.add_role("LoyaltyEmbedded")
    for pvm in sm.get_all_permission_views():
        if pvm.permission.name in ("can_read", "can_explore_json", "can_csv", "can_samples") and pvm.view_menu.name in ("Chart", "Dashboard", "Dataset", "Database", "Superset", "SQLLab", "Log"):
            sm.add_permission_role(role, pvm)
    db.session.commit()
PY
echo "superset ready"
