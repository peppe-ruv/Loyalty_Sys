-- V6 — audit a prova di manomissione (F2-GRC-07 parte 1, ADR-043, ADR-044, docs/18 §3.15 punto 5, M8.12).
-- Ogni voce di `audit_entry` porta l'hash della precedente dello stesso servizio (una catena per `service`, così gli
-- accodamenti di servizi diversi non si contendono nulla). La catena la calcola il database all'inserimento
-- (trigger `audit_entry_chain`): vale per chiunque inserisca, anche per la versione precedente di insight durante un
-- aggiornamento senza fermo (ADR-038: chi non conosce le colonne nuove continua a funzionare e resta in catena).
-- La verifica (`GET /v1/audit/verify`, AuditChainVerifier) ricalcola tutto in Java, in modo indipendente da queste
-- funzioni: chi altera il database non può "aggiustare" anche il verificatore.
--
-- Forma canonica (versione 1, docs/servizi/insight-service.md §5 «Audit a catena di hash»). Ogni campo è una
-- netstring `<byte UTF-8 in decimale>:<valore>,`; il valore NULL è il solo carattere `~` (una netstring comincia
-- sempre con una cifra: nessuna ambiguità tra NULL, stringa vuota e confini dei campi).
--   content_hash = sha256_hex( ns('lh.audit.content.v1') ns(summary) ns(before::text) ns(after::text) )
--   entry_hash   = sha256_hex( ns('lh.audit.entry.v1') ns(service) ns(seq) ns(prev_hash) ns(id) ns(event_id)
--                              ns(at UTC 'YYYY-MM-DDTHH:MI:SS.ffffffZ') ns(actor_role) ns(actor_name)
--                              ns(entity_type) ns(entity_id) ns(action) ns(correlation_id) ns(content_hash) )
-- `before::text`/`after::text` sono la resa testuale di jsonb (chiavi ordinate e senza duplicati, spaziatura fissa);
-- `seq` in decimale; hash in esadecimale minuscolo; `prev_hash` della prima voce = 64 zeri (genesi).
-- Il contenuto (summary, before, after) entra con il proprio hash: l'anonimizzazione di un membro (F-MBR-05) può
-- riscriverlo senza spezzare la catena, e la voce resta marcata `redacted_at` (SPEC-GAP: Q-401).
--
-- Sola inserzione (ADR-043). Nel profilo demo c'è un solo ruolo di database, quindi la barriera sono i trigger:
-- UPDATE, DELETE e TRUNCATE sono rifiutati, tranne i tre percorsi controllati qui sotto, che alzano un flag locale alla
-- transazione (`loyaltyhub.audit_write`): anonimizzazione (`audit_redact`, solo i campi di contenuto), retention
-- (`audit_purge_before`, solo la parte più vecchia di ogni catena, con un'ancora PURGE) e reset della demo
-- (`audit_reset`). Obiettivo `enterprise` (ruoli owner/app, docs/18 §3.10 punto 4, M8.5): il ruolo applicativo ha
-- solo SELECT e INSERT su `audit_entry`/`audit_anchor`, le tre funzioni diventano SECURITY DEFINER del ruolo owner con
-- EXECUTE concesso all'app (`audit_reset` mai concessa: `/v1/demo/**` non esiste in enterprise). Contro chi ha
-- accesso diretto al database protegge la catena con le ancore (`audit_anchor`), non il trigger.

-- ---------- 1. colonne e tabelle ----------

ALTER TABLE audit_entry ADD COLUMN IF NOT EXISTS seq bigint;               -- posizione nella catena del servizio
ALTER TABLE audit_entry ADD COLUMN IF NOT EXISTS prev_hash text;           -- entry_hash della voce seq - 1 (genesi: 64 zeri)
ALTER TABLE audit_entry ADD COLUMN IF NOT EXISTS content_hash text;        -- hash di summary, before, after
ALTER TABLE audit_entry ADD COLUMN IF NOT EXISTS entry_hash text;          -- hash della voce (forma canonica sopra)
ALTER TABLE audit_entry ADD COLUMN IF NOT EXISTS redacted_at timestamptz;  -- contenuto riscritto dall'anonimizzazione

-- Testa di ogni catena: ultima seq e ultimo hash. È la riga che serializza gli accodamenti di un servizio
-- (SELECT … FOR UPDATE nel trigger); la verifica la confronta con l'ultima voce.
CREATE TABLE IF NOT EXISTS audit_chain_head (
  service    text PRIMARY KEY,
  seq        bigint NOT NULL,
  entry_hash text NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- Ancore (sola inserzione): punti della catena registrati a parte. DAILY = job giornaliero dopo una verifica,
-- PURGE = ultima voce cancellata dalla retention (la prima voce rimasta vi si aggancia), BACKFILL = stato delle voci
-- esistenti al momento di questa migrazione. Firma ed esportazione su archivio immutabile: Q-400 (fuori perimetro).
CREATE TABLE IF NOT EXISTS audit_anchor (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  service     text NOT NULL,
  seq         bigint NOT NULL,
  entry_hash  text NOT NULL,
  kind        text NOT NULL CHECK (kind IN ('DAILY', 'PURGE', 'BACKFILL')),
  anchored_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_audit_anchor_service ON audit_anchor (service, seq);

-- ---------- 2. forma canonica ----------

CREATE OR REPLACE FUNCTION audit_ns(v text) RETURNS text
  LANGUAGE sql STABLE SET search_path FROM CURRENT AS
$$ SELECT CASE WHEN v IS NULL THEN '~' ELSE octet_length(convert_to(v, 'UTF8'))::text || ':' || v || ',' END $$;

CREATE OR REPLACE FUNCTION audit_content_hash(p_summary text, p_before jsonb, p_after jsonb) RETURNS text
  LANGUAGE sql STABLE SET search_path FROM CURRENT AS
$$
  SELECT encode(sha256(convert_to(
           audit_ns('lh.audit.content.v1') || audit_ns(p_summary) || audit_ns(p_before::text) || audit_ns(p_after::text),
           'UTF8')), 'hex')
$$;

CREATE OR REPLACE FUNCTION audit_entry_hash(p_service text, p_seq bigint, p_prev_hash text, p_id text, p_event_id text,
                                            p_at timestamptz, p_actor_role text, p_actor_name text,
                                            p_entity_type text, p_entity_id text, p_action text,
                                            p_correlation_id text, p_content_hash text) RETURNS text
  LANGUAGE sql STABLE SET search_path FROM CURRENT AS
$$
  SELECT encode(sha256(convert_to(
           audit_ns('lh.audit.entry.v1') || audit_ns(p_service) || audit_ns(p_seq::text) || audit_ns(p_prev_hash)
           || audit_ns(p_id) || audit_ns(p_event_id)
           || audit_ns(to_char(p_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'))
           || audit_ns(p_actor_role) || audit_ns(p_actor_name) || audit_ns(p_entity_type) || audit_ns(p_entity_id)
           || audit_ns(p_action) || audit_ns(p_correlation_id) || audit_ns(p_content_hash),
           'UTF8')), 'hex')
$$;

-- ---------- 3. riempimento delle voci esistenti ----------
-- Deterministico: per servizio, in ordine di `id` (ULID assegnato all'arrivo in insight, quindi ordine d'arrivo),
-- confronto binario. Lo stato attestato è quello del database al momento della migrazione: un'ancora BACKFILL per
-- servizio lo fissa. Le voci nuove si agganciano alla testa così ottenuta.

DO $$
DECLARE
  r            record;
  cur_service  text;
  cur_seq      bigint := 0;
  cur_hash     text := repeat('0', 64);
  c_hash       text;
  e_hash       text;
BEGIN
  FOR r IN SELECT * FROM audit_entry ORDER BY service COLLATE "C", id COLLATE "C" LOOP
    IF cur_service IS DISTINCT FROM r.service THEN
      IF cur_service IS NOT NULL THEN
        INSERT INTO audit_chain_head (service, seq, entry_hash) VALUES (cur_service, cur_seq, cur_hash);
      END IF;
      cur_service := r.service;
      cur_seq := 0;
      cur_hash := repeat('0', 64);
    END IF;
    cur_seq := cur_seq + 1;
    c_hash := audit_content_hash(r.summary, r.before, r.after);
    e_hash := audit_entry_hash(r.service, cur_seq, cur_hash, r.id, r.event_id, r.at, r.actor_role, r.actor_name,
                               r.entity_type, r.entity_id, r.action, r.correlation_id, c_hash);
    UPDATE audit_entry SET seq = cur_seq, prev_hash = cur_hash, content_hash = c_hash, entry_hash = e_hash
     WHERE id = r.id;
    cur_hash := e_hash;
  END LOOP;
  IF cur_service IS NOT NULL THEN
    INSERT INTO audit_chain_head (service, seq, entry_hash) VALUES (cur_service, cur_seq, cur_hash);
  END IF;
END $$;

INSERT INTO audit_anchor (service, seq, entry_hash, kind)
SELECT service, seq, entry_hash, 'BACKFILL' FROM audit_chain_head;

ALTER TABLE audit_entry ALTER COLUMN seq SET NOT NULL;
ALTER TABLE audit_entry ALTER COLUMN prev_hash SET NOT NULL;
ALTER TABLE audit_entry ALTER COLUMN content_hash SET NOT NULL;
ALTER TABLE audit_entry ALTER COLUMN entry_hash SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_audit_entry_chain ON audit_entry (service, seq);

-- ---------- 4. accodamento in catena ----------
-- Il trigger ignora i valori di catena proposti da chi inserisce e li calcola. Serializzazione per servizio: la riga
-- di testa è bloccata (FOR UPDATE) fino al commit, quindi due inserimenti dello stesso servizio non possono leggere
-- la stessa testa. Idempotenza: un event_id già registrato (riconsegna, anche dopo la retention dell'event store)
-- non crea un secondo anello; il controllo è fatto dopo il blocco, quando un duplicato concorrente ha già concluso.

CREATE OR REPLACE FUNCTION audit_entry_chain() RETURNS trigger
  LANGUAGE plpgsql SET search_path FROM CURRENT AS
$$
DECLARE
  head_seq  bigint;
  head_hash text;
BEGIN
  IF NEW.service IS NULL THEN
    RAISE EXCEPTION 'audit_entry.service è obbligatorio' USING ERRCODE = 'not_null_violation';
  END IF;
  INSERT INTO audit_chain_head (service, seq, entry_hash) VALUES (NEW.service, 0, repeat('0', 64))
    ON CONFLICT (service) DO NOTHING;
  SELECT h.seq, h.entry_hash INTO head_seq, head_hash FROM audit_chain_head h WHERE h.service = NEW.service FOR UPDATE;
  IF EXISTS (SELECT 1 FROM audit_entry e WHERE e.event_id = NEW.event_id) THEN
    RETURN NULL;
  END IF;
  NEW.seq := head_seq + 1;
  NEW.prev_hash := head_hash;
  NEW.redacted_at := NULL;
  NEW.content_hash := audit_content_hash(NEW.summary, NEW.before, NEW.after);
  NEW.entry_hash := audit_entry_hash(NEW.service, NEW.seq, NEW.prev_hash, NEW.id, NEW.event_id, NEW.at,
                                     NEW.actor_role, NEW.actor_name, NEW.entity_type, NEW.entity_id, NEW.action,
                                     NEW.correlation_id, NEW.content_hash);
  UPDATE audit_chain_head SET seq = NEW.seq, entry_hash = NEW.entry_hash, updated_at = now()
   WHERE service = NEW.service;
  RETURN NEW;
END
$$;

-- ---------- 5. sola inserzione ----------

CREATE OR REPLACE FUNCTION audit_reject_write() RETURNS trigger
  LANGUAGE plpgsql SET search_path FROM CURRENT AS
$$
BEGIN
  RAISE EXCEPTION '% è in sola inserzione (ADR-043): % rifiutato', TG_TABLE_NAME, TG_OP
    USING ERRCODE = 'insufficient_privilege';
END
$$;

-- UPDATE: solo l'anonimizzazione, solo summary/before/after; la catena resta valida (content_hash invariato).
CREATE OR REPLACE FUNCTION audit_entry_guard_update() RETURNS trigger
  LANGUAGE plpgsql SET search_path FROM CURRENT AS
$$
BEGIN
  IF current_setting('loyaltyhub.audit_write', true) IS DISTINCT FROM 'redact' THEN
    RAISE EXCEPTION 'audit_entry è in sola inserzione (ADR-043): UPDATE rifiutato'
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  IF (NEW.id, NEW.event_id, NEW.at, NEW.actor_role, NEW.actor_name, NEW.service, NEW.entity_type, NEW.entity_id,
      NEW.action, NEW.correlation_id, NEW.seq, NEW.prev_hash, NEW.content_hash, NEW.entry_hash)
     IS DISTINCT FROM
     (OLD.id, OLD.event_id, OLD.at, OLD.actor_role, OLD.actor_name, OLD.service, OLD.entity_type, OLD.entity_id,
      OLD.action, OLD.correlation_id, OLD.seq, OLD.prev_hash, OLD.content_hash, OLD.entry_hash) THEN
    RAISE EXCEPTION 'audit_entry: l''anonimizzazione riscrive solo summary, before e after'
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  NEW.redacted_at := now();
  RETURN NEW;
END
$$;

-- DELETE: solo retention o reset della demo (flag alzato dalle funzioni controllate).
CREATE OR REPLACE FUNCTION audit_entry_guard_delete() RETURNS trigger
  LANGUAGE plpgsql SET search_path FROM CURRENT AS
$$
BEGIN
  IF coalesce(current_setting('loyaltyhub.audit_write', true), '') NOT IN ('purge', 'reset') THEN
    RAISE EXCEPTION 'audit_entry è in sola inserzione (ADR-043): DELETE ammesso solo dalla retention'
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  RETURN NULL;
END
$$;

-- Dopo un DELETE: le voci cancellate sono la parte più vecchia di ogni catena (nessuna voce rimasta le precede).
-- Un buco in mezzo renderebbe la catena non verificabile.
CREATE OR REPLACE FUNCTION audit_entry_check_prefix() RETURNS trigger
  LANGUAGE plpgsql SET search_path FROM CURRENT AS
$$
BEGIN
  IF EXISTS (SELECT 1 FROM purged p JOIN audit_entry r ON r.service = p.service AND r.seq < p.seq) THEN
    RAISE EXCEPTION 'audit_entry: la retention cancella solo le voci più vecchie di ogni catena'
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  RETURN NULL;
END
$$;

-- Ancore: nessuna modifica, cancellazione solo dal reset della demo.
CREATE OR REPLACE FUNCTION audit_anchor_guard_delete() RETURNS trigger
  LANGUAGE plpgsql SET search_path FROM CURRENT AS
$$
BEGIN
  IF current_setting('loyaltyhub.audit_write', true) IS DISTINCT FROM 'reset' THEN
    RAISE EXCEPTION 'audit_anchor è in sola inserzione (ADR-043): DELETE rifiutato'
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  RETURN NULL;
END
$$;

DROP TRIGGER IF EXISTS audit_entry_chain ON audit_entry;
CREATE TRIGGER audit_entry_chain BEFORE INSERT ON audit_entry
  FOR EACH ROW EXECUTE FUNCTION audit_entry_chain();
DROP TRIGGER IF EXISTS audit_entry_guard_update ON audit_entry;
CREATE TRIGGER audit_entry_guard_update BEFORE UPDATE ON audit_entry
  FOR EACH ROW EXECUTE FUNCTION audit_entry_guard_update();
DROP TRIGGER IF EXISTS audit_entry_guard_delete ON audit_entry;
CREATE TRIGGER audit_entry_guard_delete BEFORE DELETE ON audit_entry
  FOR EACH STATEMENT EXECUTE FUNCTION audit_entry_guard_delete();
DROP TRIGGER IF EXISTS audit_entry_check_prefix ON audit_entry;
CREATE TRIGGER audit_entry_check_prefix AFTER DELETE ON audit_entry
  REFERENCING OLD TABLE AS purged FOR EACH STATEMENT EXECUTE FUNCTION audit_entry_check_prefix();
DROP TRIGGER IF EXISTS audit_entry_no_truncate ON audit_entry;
CREATE TRIGGER audit_entry_no_truncate BEFORE TRUNCATE ON audit_entry
  FOR EACH STATEMENT EXECUTE FUNCTION audit_reject_write();

DROP TRIGGER IF EXISTS audit_anchor_no_update ON audit_anchor;
CREATE TRIGGER audit_anchor_no_update BEFORE UPDATE ON audit_anchor
  FOR EACH STATEMENT EXECUTE FUNCTION audit_reject_write();
DROP TRIGGER IF EXISTS audit_anchor_guard_delete ON audit_anchor;
CREATE TRIGGER audit_anchor_guard_delete BEFORE DELETE ON audit_anchor
  FOR EACH STATEMENT EXECUTE FUNCTION audit_anchor_guard_delete();
DROP TRIGGER IF EXISTS audit_anchor_no_truncate ON audit_anchor;
CREATE TRIGGER audit_anchor_no_truncate BEFORE TRUNCATE ON audit_anchor
  FOR EACH STATEMENT EXECUTE FUNCTION audit_reject_write();

-- ---------- 6. percorsi controllati ----------

-- Anonimizzazione di una voce (MemberRedactionRepository, F-MBR-05): solo i campi di contenuto.
CREATE OR REPLACE FUNCTION audit_redact(p_id text, p_summary text, p_before jsonb, p_after jsonb) RETURNS boolean
  LANGUAGE plpgsql SET search_path FROM CURRENT AS
$$
DECLARE
  n integer;
BEGIN
  PERFORM set_config('loyaltyhub.audit_write', 'redact', true);
  UPDATE audit_entry SET summary = p_summary, before = p_before, after = p_after WHERE id = p_id;
  GET DIAGNOSTICS n = ROW_COUNT;
  PERFORM set_config('loyaltyhub.audit_write', '', true);
  RETURN n > 0;
END
$$;

-- Retention (RetentionJob): per ogni catena cancella la parte più vecchia, cioè le voci fino alla prima con
-- `at >= p_threshold` esclusa. Una voce vecchia che segue una più recente resta finché non scade anche quella
-- (scelta conservativa: la catena non si buca mai). L'ultima voce cancellata diventa un'ancora PURGE, a cui la verifica
-- aggancia la prima voce rimasta.
CREATE OR REPLACE FUNCTION audit_purge_before(p_threshold timestamptz) RETURNS integer
  LANGUAGE plpgsql SET search_path FROM CURRENT AS
$$
DECLARE
  svc        record;
  keep_from  bigint;
  last_seq   bigint;
  last_hash  text;
  n          integer;
  purged     integer := 0;
BEGIN
  PERFORM set_config('loyaltyhub.audit_write', 'purge', true);
  FOR svc IN SELECT DISTINCT e.service FROM audit_entry e WHERE e.at < p_threshold ORDER BY 1 LOOP
    SELECT min(e.seq) INTO keep_from FROM audit_entry e WHERE e.service = svc.service AND e.at >= p_threshold;
    SELECT e.seq, e.entry_hash INTO last_seq, last_hash FROM audit_entry e
     WHERE e.service = svc.service AND (keep_from IS NULL OR e.seq < keep_from)
     ORDER BY e.seq DESC LIMIT 1;
    CONTINUE WHEN last_seq IS NULL;
    INSERT INTO audit_anchor (service, seq, entry_hash, kind) VALUES (svc.service, last_seq, last_hash, 'PURGE');
    DELETE FROM audit_entry e WHERE e.service = svc.service AND e.seq <= last_seq;
    GET DIAGNOSTICS n = ROW_COUNT;
    purged := purged + n;
  END LOOP;
  PERFORM set_config('loyaltyhub.audit_write', '', true);
  RETURN purged;
END
$$;

-- Reset della demo (InsightReset, solo profilo demo): la demo riparte dalla genesi, catene e ancore comprese.
CREATE OR REPLACE FUNCTION audit_reset() RETURNS integer
  LANGUAGE plpgsql SET search_path FROM CURRENT AS
$$
DECLARE
  n integer;
BEGIN
  PERFORM set_config('loyaltyhub.audit_write', 'reset', true);
  DELETE FROM audit_entry;
  GET DIAGNOSTICS n = ROW_COUNT;
  DELETE FROM audit_anchor;
  DELETE FROM audit_chain_head;
  PERFORM set_config('loyaltyhub.audit_write', '', true);
  RETURN n;
END
$$;
