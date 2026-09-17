"""Configurazione Apache Superset per Loyalty Hub (RF-122, RF-123).
Metadati su Postgres (RDS), cache e code Celery su Redis, dashboard incorporate nel backoffice con guest token e
row-level security, SSO OIDC per gli analisti, nessun accesso anonimo, CSP compatibile con l'iframe del backoffice."""
import os
from datetime import timedelta

SECRET_KEY = os.environ["SUPERSET_SECRET_KEY"]
SQLALCHEMY_DATABASE_URI = os.environ.get("SUPERSET_DB_URI", "postgresql+psycopg2://%s:%s@%s:5432/superset" % (
    os.environ.get("SUPERSET_DB_USER", "superset"), os.environ.get("SUPERSET_DB_PASSWORD", "superset"), os.environ.get("SUPERSET_DB_HOST", "postgres")))
REDIS_HOST = os.environ.get("REDIS_HOST", "redis")

# --- affidabilità: cache e code su Redis, worker Celery separati, timeout espliciti
CACHE_CONFIG = {"CACHE_TYPE": "RedisCache", "CACHE_DEFAULT_TIMEOUT": 300, "CACHE_KEY_PREFIX": "superset_", "CACHE_REDIS_URL": f"redis://{REDIS_HOST}:6379/1"}
DATA_CACHE_CONFIG = {**CACHE_CONFIG, "CACHE_DEFAULT_TIMEOUT": 600, "CACHE_REDIS_URL": f"redis://{REDIS_HOST}:6379/2"}
FILTER_STATE_CACHE_CONFIG = {**CACHE_CONFIG, "CACHE_REDIS_URL": f"redis://{REDIS_HOST}:6379/3"}
EXPLORE_FORM_DATA_CACHE_CONFIG = {**CACHE_CONFIG, "CACHE_REDIS_URL": f"redis://{REDIS_HOST}:6379/4"}


class CeleryConfig:
    broker_url = f"redis://{REDIS_HOST}:6379/0"
    result_backend = f"redis://{REDIS_HOST}:6379/0"
    imports = ("superset.sql_lab", "superset.tasks.scheduler", "superset.tasks.thumbnails", "superset.tasks.cache")
    worker_prefetch_multiplier = 1
    task_acks_late = True
    beat_schedule = {
        "reports.scheduler": {"task": "reports.scheduler", "schedule": 60.0},
        "cache-warmup-kpi": {"task": "cache-warmup", "schedule": 900.0, "kwargs": {"strategy_name": "top_n_dashboards", "top_n": 5, "since": "7 days ago"}},
    }


CELERY_CONFIG = CeleryConfig
SQLLAB_ASYNC_TIME_LIMIT_SEC = 300
SUPERSET_WEBSERVER_TIMEOUT = 120

# --- incorporazione nel backoffice (RF-123)
FEATURE_FLAGS = {"EMBEDDED_SUPERSET": True, "DASHBOARD_RBAC": True, "ALERT_REPORTS": True, "DASHBOARD_NATIVE_FILTERS": True, "ESTIMATE_QUERY_COST": True}
GUEST_ROLE_NAME = "LoyaltyEmbedded"          # ruolo con soli permessi di lettura sui dataset loyalty.*
GUEST_TOKEN_JWT_SECRET = os.environ.get("SUPERSET_GUEST_JWT_SECRET", SECRET_KEY)
GUEST_TOKEN_JWT_EXP_SECONDS = 300
BACKOFFICE_ORIGIN = os.environ.get("BACKOFFICE_ORIGIN", "http://localhost:3002")
ENABLE_CORS = True
CORS_OPTIONS = {"supports_credentials": True, "allow_headers": ["*"], "resources": ["*"], "origins": [BACKOFFICE_ORIGIN]}
TALISMAN_ENABLED = True
TALISMAN_CONFIG = {
    "content_security_policy": {
        "default-src": ["'self'"], "img-src": ["'self'", "data:", "blob:"], "worker-src": ["'self'", "blob:"], "connect-src": ["'self'"],
        "object-src": "'none'", "style-src": ["'self'", "'unsafe-inline'"], "script-src": ["'self'", "'strict-dynamic'"],
        "frame-ancestors": ["'self'", BACKOFFICE_ORIGIN],
    },
    "content_security_policy_nonce_in": ["script-src"], "force_https": os.environ.get("SUPERSET_FORCE_HTTPS", "false") == "true", "session_cookie_secure": False,
}
HTTP_HEADERS = {"X-Frame-Options": "ALLOW-FROM " + BACKOFFICE_ORIGIN}
PUBLIC_ROLE_LIKE = None                      # nessun accesso anonimo

# --- SSO OIDC per gli analisti (RF-43): AUTH_OID con lo IAM aziendale; in locale login DB
from flask_appbuilder.security.manager import AUTH_DB, AUTH_OAUTH  # noqa: E402
if os.environ.get("OIDC_CLIENT_ID"):
    AUTH_TYPE = AUTH_OAUTH
    AUTH_USER_REGISTRATION = True
    AUTH_USER_REGISTRATION_ROLE = "Gamma"
    AUTH_ROLES_MAPPING = {"loyalty-platform-admin": ["Admin"], "loyalty-marketing": ["Alpha"], "loyalty-ops": ["Gamma"]}
    AUTH_ROLES_SYNC_AT_LOGIN = True
    OAUTH_PROVIDERS = [{"name": "sso", "icon": "fa-key", "token_key": "access_token", "remote_app": {
        "client_id": os.environ["OIDC_CLIENT_ID"], "client_secret": os.environ["OIDC_CLIENT_SECRET"],
        "server_metadata_url": os.environ["OIDC_DISCOVERY_URL"], "client_kwargs": {"scope": "openid email profile groups"}}}]
else:
    AUTH_TYPE = AUTH_DB

# --- limiti e sicurezza delle query
ROW_LIMIT = 50000
SQL_MAX_ROW = 100000
DISPLAY_MAX_ROW = 10000
PREVENT_UNSAFE_DB_CONNECTIONS = True
ALERT_REPORTS_NOTIFICATION_DRY_RUN = False
WEBDRIVER_BASEURL = os.environ.get("SUPERSET_INTERNAL_URL", "http://superset:8088/")
