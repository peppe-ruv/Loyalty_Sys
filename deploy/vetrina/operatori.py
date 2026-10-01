#!/usr/bin/env python3
"""Account operatore nominativi della vetrina enterprise (F2-IAM-01, ADR-049; Q-618 e Q-624, decise).

Automatizza la procedura di deploy/idp/README.md («Creare un account operatore nominativo») per l'azzeramento
settimanale, che ricrea gli account (Q-624). Per ogni riga dell'elenco crea l'utente nel realm `loyaltyhub` con:
  - azioni richieste UPDATE_PASSWORD e CONFIGURE_TOTP (password e OTP scelti dall'operatore al primo accesso);
  - una password temporanea casuale, scritta SOLO in un file 0600 (creato in modo atomico) da consegnare fuori banda;
  - il ruolo operatore indicato e MFA_REQUIRED_ROLE, assegnati direttamente all'utente (l'OTP scatta solo con questo).
Un utente già presente non si modifica (idempotente). Nessuna password su stdout, negli argomenti o nei log: la
password di amministrazione arriva da file, il token resta in memoria. Solo https, oppure http verso loopback.

Elenco (una riga per account, `#` per i commenti):  <nome utente> <RUOLO> <e-mail>
Uscita: 0 ok · 1 errore di Keycloak · 2 uso o elenco non validi.
"""
import argparse
import json
import os
import re
import secrets
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request

REALM = "loyaltyhub"
OPERATOR_ROLES = ("ADMIN", "MARKETING", "LEGAL", "CARE", "ANALYST")
MFA_ROLE = "MFA_REQUIRED_ROLE"
USERNAME_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{1,62}$")
EMAIL_RE = re.compile(r"^[^@\s]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+$")
LOOPBACK = {"localhost", "127.0.0.1", "::1"}


class UsageError(Exception):
    pass


class KeycloakError(Exception):
    pass


def parse_list(path):
    ops, seen = [], set()
    # Nomi ed e-mail degli operatori sono dati personali: il file resta leggibile solo dal proprietario.
    if os.lstat(path).st_mode & 0o077 or os.path.islink(path):
        raise UsageError(f"{path}: serve un file regolare con permessi 0600")
    with open(path, encoding="utf-8") as fh:
        for n, raw in enumerate(fh, 1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split()
            if len(parts) != 3:
                raise UsageError(f"{path} riga {n}: atteso «nome-utente RUOLO e-mail»")
            user, role, email = parts
            if not USERNAME_RE.match(user):
                raise UsageError(f"{path} riga {n}: nome utente non valido")
            if role not in OPERATOR_ROLES:
                raise UsageError(f"{path} riga {n}: ruolo non ammesso {role} (ammessi: {', '.join(OPERATOR_ROLES)})")
            if not EMAIL_RE.match(email):
                raise UsageError(f"{path} riga {n}: e-mail non valida")
            if user in seen:
                raise UsageError(f"{path} riga {n}: utente ripetuto {user}")
            seen.add(user)
            ops.append((user, role, email))
    return ops


def validate_url(raw):
    u = urllib.parse.urlsplit(raw)
    if u.username or u.password or u.query or u.fragment:
        raise UsageError("--keycloak: niente credenziali, query o frammento nell'URL")
    if u.scheme == "https":
        return raw.rstrip("/")
    if u.scheme == "http" and u.hostname in LOOPBACK:
        return raw.rstrip("/")
    raise UsageError("--keycloak: serve https (http solo verso localhost, 127.0.0.1 o ::1)")


def read_secret_file(path):
    st = os.lstat(path)
    if not os.path.isfile(path) or os.path.islink(path):
        raise UsageError(f"{path}: non è un file regolare")
    if st.st_mode & 0o077:
        raise UsageError(f"{path}: permessi troppo larghi, serve 0600")
    with open(path, encoding="utf-8") as fh:
        value = fh.read().strip()
    if not value:
        raise UsageError(f"{path}: vuoto")
    return value


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):  # il token non cambia mai host
        return None


OPENER = urllib.request.build_opener(NoRedirect)


def call(method, url, token=None, body=None, form=None):
    headers = {"Accept": "application/json"}
    data = None
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    elif body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with OPENER.open(req, timeout=30) as resp:
            raw = resp.read()
            return resp.status, resp.headers, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        # Mai il corpo della richiesta nel messaggio: può contenere una password.
        raise KeycloakError(f"{method} {urllib.parse.urlsplit(url).path}: HTTP {e.code}") from None
    except urllib.error.URLError as e:
        raise KeycloakError(f"{method} {urllib.parse.urlsplit(url).path}: {e.reason}") from None


def admin_token(base, user, password):
    _, _, data = call("POST", f"{base}/realms/master/protocol/openid-connect/token",
                      form={"client_id": "admin-cli", "grant_type": "password", "username": user, "password": password})
    if not data or "access_token" not in data:
        raise KeycloakError("token di amministrazione assente nella risposta")
    return data["access_token"]


def write_passwords(path, created):
    directory = os.path.dirname(os.path.abspath(path))
    if os.path.islink(path) or os.path.isdir(path):
        raise UsageError(f"{path}: collegamento simbolico o cartella")
    fd, tmp = tempfile.mkstemp(prefix=".operator-passwords.", dir=directory)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            fh.write("# Password temporanee della vetrina (UPDATE_PASSWORD e CONFIGURE_TOTP al primo accesso).\n")
            fh.write("# Consegnare fuori banda a ciascun operatore e cancellare il file.\n")
            for user, pw in created:
                fh.write(f"{user}: {pw}\n")
        os.replace(tmp, path)
    except BaseException:
        if os.path.exists(tmp):
            os.unlink(tmp)
        raise


def main(argv=None):
    p = argparse.ArgumentParser(description="Account operatore nominativi della vetrina (Q-618, Q-624)")
    p.add_argument("--keycloak", required=True, help="URL di Keycloak, es. http://127.0.0.1:8180")
    p.add_argument("--admin-user", default="admin")
    p.add_argument("--admin-password-file", required=True)
    p.add_argument("--list", required=True)
    p.add_argument("--out", required=True, help="file 0600 delle password temporanee")
    args = p.parse_args(argv)
    try:
        base = validate_url(args.keycloak)
        ops = parse_list(args.list)
        password = read_secret_file(args.admin_password_file)
    except (UsageError, OSError) as e:
        print(f"Errore: {e}", file=sys.stderr)
        return 2
    if not ops:
        print("Nessun account nell'elenco.")
        return 0
    try:
        token = admin_token(base, args.admin_user, password)
        del password
        admin = f"{base}/admin/realms/{REALM}"
        roles = {}
        for name in (MFA_ROLE,) + OPERATOR_ROLES:
            _, _, rep = call("GET", f"{admin}/roles/{urllib.parse.quote(name)}", token)
            roles[name] = {"id": rep["id"], "name": rep["name"]}
        created = []
        for user, role, email in ops:
            q = urllib.parse.urlencode({"username": user, "exact": "true"})
            _, _, found = call("GET", f"{admin}/users?{q}", token)
            if found:
                print(f"presente: {user} (non modificato)")
                continue
            call("POST", f"{admin}/users", token, body={
                "username": user, "email": email, "enabled": True, "emailVerified": False,
                "requiredActions": ["UPDATE_PASSWORD", "CONFIGURE_TOTP"],
            })
            _, _, found = call("GET", f"{admin}/users?{q}", token)
            if not found:
                raise KeycloakError(f"utente {user} non trovato dopo la creazione")
            uid = found[0]["id"]
            temp = secrets.token_urlsafe(18)
            call("PUT", f"{admin}/users/{uid}/reset-password", token,
                 body={"type": "password", "value": temp, "temporary": True})
            call("POST", f"{admin}/users/{uid}/role-mappings/realm", token, body=[roles[role], roles[MFA_ROLE]])
            created.append((user, temp))
            print(f"creato: {user} ({role} + {MFA_ROLE}, password temporanea nel file)")
        if created:
            write_passwords(args.out, created)
            print(f"Password temporanee in {args.out} (permessi 0600): consegnale fuori banda e cancella il file.")
        return 0
    except (KeycloakError, KeyError, TypeError) as e:
        print(f"Errore: {e}", file=sys.stderr)
        return 1
    except UsageError as e:
        print(f"Errore: {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
