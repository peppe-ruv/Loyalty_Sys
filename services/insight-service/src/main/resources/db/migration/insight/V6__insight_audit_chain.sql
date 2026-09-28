-- V6 — audit a catena di hash (F2-GRC-07 parte 1, ADR-043, ADR-044, ADR-038, docs/18 §3.15 punto 5, M8.12a).
-- Ogni voce di `audit_entry` porta l'hash della precedente dello stesso servizio (una catena per `service`, così gli
-- accodamenti di servizi diversi non si contendono nulla). La catena la calcola il database all'inserimento
-- (trigger `audit_entry_chain`), per chiunque inserisca. La verifica (`GET /v1/audit/verify`, AuditChainVerifier)
-- ricalcola tutto in Java, in modo indipendente da queste funzioni.
--
-- Forma canonica, versione 1 (normativa in docs/servizi/insight-service.md §5 «Audit a catena di hash»). Ogni campo è
-- una netstring `<byte UTF-8 in decimale>:<valore>,`; il valore NULL è il solo carattere `~`.
--   content_hash = sha256_hex( ns('lh.audit.content.v1') ns(summary) ns(before::text) ns(after::text) )
--   entry_hash   = sha256_hex( ns('lh.audit.entry.v1') ns(service) ns(seq) ns(prev_hash) ns(id) ns(event_id)
--                              ns(at UTC 'YYYY-MM-DDTHH:MI:SS.ffffffZ') ns(actor_role) ns(actor_name)
--                              ns(entity_type) ns(entity_id) ns(action) ns(correlation_id) ns(content_hash) )
-- `before::text`/`after::text`: resa testuale di jsonb (chiavi ordinate per lunghezza e poi per byte, senza duplicati,
-- separatori ", " e ": ", numeri come memorizzati). `seq` in decimale; hash esadecimali minuscoli; genesi = 64 zeri.
--
-- Prove delle anonimizzazioni. Ogni riscrittura del contenuto di una voce accoda una voce REDACT nella catena riservata
-- `audit.redaction`, che scrive solo il database (un evento del bus che la rivendica è rifiutato: EventIngestService e il
-- trigger di accodamento).
--
-- Fase expand (ADR-038, regola 14). Durante un aggiornamento senza fermo la versione precedente di insight gira sullo
-- schema V6 e scrive `audit_entry` come prima. Perciò questa migrazione ammette anche senza flag:
--   - l'INSERT (la catena la calcola comunque il trigger);
--   - l'UPDATE dei soli campi di contenuto (summary, before, after): l'anonimizzazione di F-MBR-05. I campi della voce e
--     della catena restano congelati, la voce è marcata `redacted_at` e il trigger accoda la prova REDACT;
--   - il DELETE della sola parte iniziale di ogni catena (retention e reset della demo della versione precedente): il
--     trigger sul risultato lascia un'ancora PURGE sull'ultima voce cancellata di ogni servizio, con l'istante della
--     voce più recente cancellata (`entry_at`); la verifica segnala una cancellazione di voci più giovani dell'età minima
--     (PURGE_TOO_RECENT). Una seq si cancella una volta sola: un'ancora PURGE già presente per quella posizione è
--     falsificata e il DELETE è rifiutato. È un reset (via anche teste e ancore) solo il DELETE che svuota la tabella
--     nella stessa transazione che ha svuotato l'event store: il reset della demo della versione precedente
--     (InsightReset, @Transactional). La retention oraria della versione precedente, che su un'installazione ferma può
--     svuotare la tabella, lascia invece le sue ancore PURGE.
--     Il DELETE per età della versione precedente è rifiutato quando una voce scaduta segue una più recente (lascerebbe
--     un buco, Q-402): nessun dato perso, il job della versione precedente riprova al giro successivo e il rilascio
--     porta la retention nuova, che cancella solo la parte iniziale.
--   Durante il rilascio l'UPDATE della versione precedente blocca prima la voce e poi la testa di `audit.redaction`,
--   il codice nuovo prima la testa e poi la voce: due anonimizzazioni concorrenti delle due versioni possono andare in
--   stallo reciproco. PostgreSQL ne annulla una, il consumer la riprova e passa (Q-403).
-- Il codice nuovo passa già dalle funzioni controllate, che alzano un flag locale alla transazione
-- (`loyaltyhub.audit_write`). La migrazione di contract (Q-403, con i ruoli owner/app di M8.5) renderà obbligatori i
-- flag, porrà un'età minima nel trigger dei DELETE e toglierà UPDATE e DELETE al ruolo applicativo.
-- SPEC-GAP: Q-403 — fino ad allora chi ha le credenziali applicative del database può riscrivere il contenuto di una
-- voce (lasciando una prova REDACT) o cancellare la parte iniziale di una catena (lasciando un'ancora PURGE che la
-- verifica segnala se le voci erano giovani); i flag sono impostazioni di sessione che quelle credenziali possono alzare.
--
-- Costo della migrazione. Tutto in una transazione: `ADD COLUMN` prende un lock ACCESS EXCLUSIVE su `audit_entry` (le
-- colonne sono senza default: nessuna riscrittura della tabella), il riempimento è un ciclo per riga (un UPDATE e due
-- SHA-256 per voce), `SET NOT NULL` scandisce la tabella una volta e l'indice unico (service, seq) una volta. Misurato
-- su PostgreSQL 14 in-process sotto carico elevato: 20 000 voci ≈ 12 s, 50 000 ≈ 27 s; dentro
-- `migrations.activeDeadlineSeconds` (1200 s) del chart. Durante la migrazione gli inserimenti nell'audit attendono.

-- ---------- 1. colonne e tabelle ----------

ALTER TABLE audit_entry
  ADD COLUMN IF NOT EXISTS seq bigint,                 -- posizione nella catena del servizio
  ADD COLUMN IF NOT EXISTS prev_hash text,             -- entry_hash della voce seq - 1 (genesi: 64 zeri)
  ADD COLUMN IF NOT EXISTS content_hash text,          -- hash di summary, before, after all'inserimento
  ADD COLUMN IF NOT EXISTS entry_hash text,            -- hash della voce (forma canonica sopra)
  ADD COLUMN IF NOT EXISTS redacted_at timestamptz;    -- contenuto riscritto dall'anonimizzazione

-- Testa di ogni catena: ultima seq e ultimo hash. È la riga che serializza gli accodamenti di un servizio
-- (SELECT … FOR UPDATE nel trigger); la verifica la confronta con l'ultima voce. La scrive solo il trigger.
CREATE TABLE IF NOT EXISTS audit_chain_head (
  service    text PRIMARY KEY,
  seq        bigint NOT NULL,
  entry_hash text NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- Ancore: punti della catena registrati a parte. DAILY = job giornaliero dopo una verifica (anche nei log, fuori dal
-- database); PURGE = ultima voce cancellata, a cui la prima voce rimasta si aggancia, con `entry_at` = istante della
-- voce più recente cancellata; BACKFILL = stato delle voci esistenti a questa migrazione. Una sola ancora per
-- (servizio, seq, tipo): più repliche non la duplicano.
CREATE TABLE IF NOT EXISTS audit_anchor (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  service     text NOT NULL,
  seq         bigint NOT NULL,
  entry_hash  text NOT NULL,
  kind        text NOT NULL CHECK (kind IN ('DAILY', 'PURGE', 'BACKFILL')),
  anchored_at timestamptz NOT NULL DEFAULT now(),
  entry_at    timestamptz
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_audit_anchor ON audit_anchor (service, seq, kind);

-- Età minima dell'audit per la retention, in giorni (docs/servizi/insight-service.md §5; AuditRetentionGuard ha lo
-- stesso valore e il profilo enterprise rifiuta di partire con una retention più corta). 365 quando arriva la retention
-- di ADR-043 (M8.12).
CREATE OR REPLACE FUNCTION audit_min_retention_days() RETURNS integer
  LANGUAGE sql IMMUTABLE AS
$$ SELECT 180 $$;

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

ALTER TABLE audit_entry
  ALTER COLUMN seq SET NOT NULL,
  ALTER COLUMN prev_hash SET NOT NULL,
  ALTER COLUMN content_hash SET NOT NULL,
  ALTER COLUMN entry_hash SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_audit_entry_chain ON audit_entry (service, seq);

-- ---------- 4. accodamento in catena ----------
-- Il trigger ignora i valori di catena proposti da chi inserisce e li calcola. Serializzazione per servizio: la riga
-- di testa è bloccata (FOR UPDATE) fino al commit, quindi due inserimenti dello stesso servizio non leggono mai la
-- stessa testa. Idempotenza: un event_id già registrato (riconsegna, anche dopo la retention dell'event store) non crea
-- un secondo anello; il controllo è fatto dopo il blocco, quando un duplicato concorrente dello stesso servizio ha già
-- concluso. Caso trascurabile: lo stesso event_id inserito in contemporanea sotto due servizi diversi (un evento porta
-- un solo `service`) farebbe avanzare la testa del secondo prima che ON CONFLICT scarti la riga; la verifica lo
-- segnalerebbe come voce mancante in coda.
-- La catena `audit.redaction` e l'azione REDACT sono riservate alle prove scritte da `audit_entry_record_redaction`.

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
  IF (NEW.service = 'audit.redaction' OR NEW.action = 'REDACT')
     AND current_setting('loyaltyhub.audit_redaction_evidence', true) IS DISTINCT FROM 'on' THEN
    RAISE EXCEPTION 'audit_entry: le prove REDACT (catena audit.redaction) le scrive solo il database'
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  PERFORM set_config('loyaltyhub.audit_chain', 'on', true);
  INSERT INTO audit_chain_head (service, seq, entry_hash) VALUES (NEW.service, 0, repeat('0', 64))
    ON CONFLICT (service) DO NOTHING;
  SELECT h.seq, h.entry_hash INTO head_seq, head_hash FROM audit_chain_head h WHERE h.service = NEW.service FOR UPDATE;
  IF EXISTS (SELECT 1 FROM audit_entry e WHERE e.event_id = NEW.event_id) THEN
    PERFORM set_config('loyaltyhub.audit_chain', '', true);
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
  PERFORM set_config('loyaltyhub.audit_chain', '', true);
  RETURN NEW;
END
$$;

-- ---------- 5. sola inserzione (fase expand) ----------

CREATE OR REPLACE FUNCTION audit_reject_write() RETURNS trigger
  LANGUAGE plpgsql SET search_path FROM CURRENT AS
$$
BEGIN
  RAISE EXCEPTION '% è in sola inserzione (ADR-043): % rifiutato', TG_TABLE_NAME, TG_OP
    USING ERRCODE = 'insufficient_privilege';
END
$$;

-- UPDATE: solo summary, before e after (anonimizzazione). Ogni altro campo e la catena sono congelati; se il contenuto
-- cambia la voce è marcata `redacted_at`.
CREATE OR REPLACE FUNCTION audit_entry_guard_update() RETURNS trigger
  LANGUAGE plpgsql SET search_path FROM CURRENT AS
$$
BEGIN
  IF (NEW.id, NEW.event_id, NEW.at, NEW.actor_role, NEW.actor_name, NEW.service, NEW.entity_type, NEW.entity_id,
      NEW.action, NEW.correlation_id, NEW.seq, NEW.prev_hash, NEW.content_hash, NEW.entry_hash)
     IS DISTINCT FROM
     (OLD.id, OLD.event_id, OLD.at, OLD.actor_role, OLD.actor_name, OLD.service, OLD.entity_type, OLD.entity_id,
      OLD.action, OLD.correlation_id, OLD.seq, OLD.prev_hash, OLD.content_hash, OLD.entry_hash) THEN
    RAISE EXCEPTION 'audit_entry è in sola inserzione (ADR-043): si riscrivono solo summary, before e after'
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  IF (NEW.summary, NEW.before, NEW.after) IS DISTINCT FROM (OLD.summary, OLD.before, OLD.after) THEN
    NEW.redacted_at := now();
  ELSE
    NEW.redacted_at := OLD.redacted_at;
  END IF;
  RETURN NEW;
END
$$;

-- Dopo ogni riscrittura del contenuto: una prova REDACT nella catena riservata `audit.redaction`, con l'hash del nuovo
-- contenuto e, se la riscrittura passa da `audit_redact`, il membro, il fatto di anonimizzazione e la correlazione.
-- Senza dati personali e senza l'hash del contenuto precedente (Q-401). La verifica esige questa prova per ogni voce
-- riscritta. `at` = `redacted_at` della voce (stessa transazione).
CREATE OR REPLACE FUNCTION audit_entry_record_redaction() RETURNS trigger
  LANGUAGE plpgsql SET search_path FROM CURRENT AS
$$
DECLARE
  member text := nullif(current_setting('loyaltyhub.audit_redact_member', true), '');
  fact text := nullif(current_setting('loyaltyhub.audit_redact_event', true), '');
  correlation text := nullif(current_setting('loyaltyhub.audit_redact_correlation', true), '');
  evidence_id text := 'redact-' || gen_random_uuid()::text;
BEGIN
  PERFORM set_config('loyaltyhub.audit_redaction_evidence', 'on', true);
  INSERT INTO audit_entry (id, event_id, at, actor_role, actor_name, service, entity_type, entity_id, action, summary,
                           before, after, correlation_id)
  VALUES (evidence_id, evidence_id, now(), 'system', 'insight', 'audit.redaction', 'AUDIT_ENTRY', NEW.id, 'REDACT',
          'Anonimizzata la voce di audit ' || NEW.service || ' n. ' || NEW.seq
            || CASE WHEN member IS NULL THEN ' (membro non indicato)' ELSE ' del membro ' || member END,
          NULL,
          jsonb_build_object('contentHash', audit_content_hash(NEW.summary, NEW.before, NEW.after),
                             'memberId', member, 'eventId', fact, 'service', NEW.service, 'seq', NEW.seq),
          correlation);
  PERFORM set_config('loyaltyhub.audit_redaction_evidence', '', true);
  RETURN NULL;
END
$$;

-- Reset della demo della versione precedente (InsightReset, @Transactional): svuota l'event store e poi l'audit nella
-- stessa transazione. Il trigger dell'event store lo annota nella transazione; la retention della versione precedente
-- svuota l'event store in una transazione sua e non lo annota mai per l'audit.
CREATE OR REPLACE FUNCTION audit_event_store_after_delete() RETURNS trigger
  LANGUAGE plpgsql SET search_path FROM CURRENT AS
$$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM event_store) THEN
    PERFORM set_config('loyaltyhub.audit_demo_reset', 'on', true);
  END IF;
  RETURN NULL;
END
$$;

-- Dopo un DELETE: le voci cancellate sono la parte iniziale di ogni catena (nessuna voce rimasta le precede), e
-- l'ultima cancellata di ogni servizio diventa un'ancora PURGE con l'istante della voce più recente cancellata
-- (`entry_at`), qualunque codice abbia cancellato. Una posizione si cancella una volta sola: un'ancora PURGE già
-- presente è falsificata e il DELETE è rifiutato. Una tabella svuotata del tutto nella stessa transazione dell'event
-- store è il reset della demo della versione precedente: via anche teste e ancore, le catene ripartono dalla genesi.
CREATE OR REPLACE FUNCTION audit_entry_after_delete() RETURNS trigger
  LANGUAGE plpgsql SET search_path FROM CURRENT AS
$$
DECLARE
  write_flag text := coalesce(current_setting('loyaltyhub.audit_write', true), '');
  forged     record;
BEGIN
  IF EXISTS (SELECT 1 FROM purged p JOIN audit_entry r ON r.service = p.service AND r.seq < p.seq) THEN
    RAISE EXCEPTION 'audit_entry: si cancella solo la parte iniziale di ogni catena'
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  IF (write_flag = 'reset' OR (write_flag <> 'purge' AND current_setting('loyaltyhub.audit_demo_reset', true) = 'on'))
     AND NOT EXISTS (SELECT 1 FROM audit_entry) THEN
    PERFORM set_config('loyaltyhub.audit_write', 'reset', true);
    DELETE FROM audit_anchor;
    DELETE FROM audit_chain_head;
    PERFORM set_config('loyaltyhub.audit_write', write_flag, true);
    RETURN NULL;
  END IF;
  SELECT a.service, a.seq INTO forged
    FROM audit_anchor a
    JOIN (SELECT p.service, max(p.seq) AS seq FROM purged p GROUP BY p.service) d
      ON d.service = a.service AND d.seq = a.seq
   WHERE a.kind = 'PURGE'
   LIMIT 1;
  IF FOUND THEN
    RAISE EXCEPTION 'audit_anchor: esiste già un''ancora PURGE per % n. % prima della cancellazione: ancora falsificata',
      forged.service, forged.seq USING ERRCODE = 'insufficient_privilege';
  END IF;
  PERFORM set_config('loyaltyhub.audit_purge_anchor', 'on', true);
  INSERT INTO audit_anchor (service, seq, entry_hash, kind, entry_at)
  SELECT DISTINCT ON (p.service) p.service, p.seq, p.entry_hash, 'PURGE', newest.at
    FROM purged p
    JOIN (SELECT q.service, max(q.at) AS at FROM purged q GROUP BY q.service) newest ON newest.service = p.service
   ORDER BY p.service, p.seq DESC;
  PERFORM set_config('loyaltyhub.audit_purge_anchor', '', true);
  RETURN NULL;
END
$$;

-- Testa: la scrive solo il trigger di accodamento; la cancella solo il reset.
CREATE OR REPLACE FUNCTION audit_chain_head_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path FROM CURRENT AS
$$
BEGIN
  IF current_setting('loyaltyhub.audit_chain', true) = 'on' THEN
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
  END IF;
  IF TG_OP = 'DELETE' AND current_setting('loyaltyhub.audit_write', true) = 'reset' THEN
    RETURN OLD;
  END IF;
  RAISE EXCEPTION 'audit_chain_head la scrive solo l''accodamento in catena: % rifiutato', TG_OP
    USING ERRCODE = 'insufficient_privilege';
END
$$;

-- Ancore all'inserimento: l'istante è sempre quello della transazione (un'ancora non si retrodata); le PURGE le scrive
-- solo il trigger dei DELETE, che ne fissa `entry_at`; le altre non hanno `entry_at`.
CREATE OR REPLACE FUNCTION audit_anchor_guard_insert() RETURNS trigger
  LANGUAGE plpgsql SET search_path FROM CURRENT AS
$$
BEGIN
  NEW.anchored_at := now();
  IF NEW.kind = 'PURGE' THEN
    IF current_setting('loyaltyhub.audit_purge_anchor', true) IS DISTINCT FROM 'on' THEN
      RAISE EXCEPTION 'audit_anchor: le ancore PURGE le scrive solo la cancellazione delle voci'
        USING ERRCODE = 'insufficient_privilege';
    END IF;
  ELSE
    NEW.entry_at := NULL;
  END IF;
  RETURN NEW;
END
$$;

-- Ancore: nessuna modifica, cancellazione solo dal reset.
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
DROP TRIGGER IF EXISTS audit_entry_record_redaction ON audit_entry;
CREATE TRIGGER audit_entry_record_redaction AFTER UPDATE ON audit_entry
  FOR EACH ROW WHEN ((NEW.summary, NEW.before, NEW.after) IS DISTINCT FROM (OLD.summary, OLD.before, OLD.after))
  EXECUTE FUNCTION audit_entry_record_redaction();
DROP TRIGGER IF EXISTS audit_entry_after_delete ON audit_entry;
CREATE TRIGGER audit_entry_after_delete AFTER DELETE ON audit_entry
  REFERENCING OLD TABLE AS purged FOR EACH STATEMENT EXECUTE FUNCTION audit_entry_after_delete();
DROP TRIGGER IF EXISTS audit_event_store_after_delete ON event_store;
CREATE TRIGGER audit_event_store_after_delete AFTER DELETE ON event_store
  FOR EACH STATEMENT EXECUTE FUNCTION audit_event_store_after_delete();
DROP TRIGGER IF EXISTS audit_entry_no_truncate ON audit_entry;
CREATE TRIGGER audit_entry_no_truncate BEFORE TRUNCATE ON audit_entry
  FOR EACH STATEMENT EXECUTE FUNCTION audit_reject_write();

DROP TRIGGER IF EXISTS audit_chain_head_guard ON audit_chain_head;
CREATE TRIGGER audit_chain_head_guard BEFORE INSERT OR UPDATE OR DELETE ON audit_chain_head
  FOR EACH ROW EXECUTE FUNCTION audit_chain_head_guard();
DROP TRIGGER IF EXISTS audit_chain_head_no_truncate ON audit_chain_head;
CREATE TRIGGER audit_chain_head_no_truncate BEFORE TRUNCATE ON audit_chain_head
  FOR EACH STATEMENT EXECUTE FUNCTION audit_reject_write();

DROP TRIGGER IF EXISTS audit_anchor_guard_insert ON audit_anchor;
CREATE TRIGGER audit_anchor_guard_insert BEFORE INSERT ON audit_anchor
  FOR EACH ROW EXECUTE FUNCTION audit_anchor_guard_insert();
DROP TRIGGER IF EXISTS audit_anchor_no_update ON audit_anchor;
CREATE TRIGGER audit_anchor_no_update BEFORE UPDATE ON audit_anchor
  FOR EACH STATEMENT EXECUTE FUNCTION audit_reject_write();
DROP TRIGGER IF EXISTS audit_anchor_guard_delete ON audit_anchor;
CREATE TRIGGER audit_anchor_guard_delete BEFORE DELETE ON audit_anchor
  FOR EACH STATEMENT EXECUTE FUNCTION audit_anchor_guard_delete();
DROP TRIGGER IF EXISTS audit_anchor_no_truncate ON audit_anchor;
CREATE TRIGGER audit_anchor_no_truncate BEFORE TRUNCATE ON audit_anchor
  FOR EACH STATEMENT EXECUTE FUNCTION audit_reject_write();

-- ---------- 6. percorsi controllati del codice nuovo ----------

-- Blocco delle anonimizzazioni: la testa della catena `audit.redaction`, presa per prima da ogni anonimizzazione
-- (MemberRedactionRepository e audit_redact). Due anonimizzazioni concorrenti che toccano le stesse righe si mettono in
-- fila invece di bloccarsi a vicenda (ordine dei lock sempre uguale).
CREATE OR REPLACE FUNCTION audit_redaction_lock() RETURNS boolean
  LANGUAGE plpgsql SET search_path FROM CURRENT AS
$$
BEGIN
  PERFORM set_config('loyaltyhub.audit_chain', 'on', true);
  INSERT INTO audit_chain_head (service, seq, entry_hash) VALUES ('audit.redaction', 0, repeat('0', 64))
    ON CONFLICT (service) DO NOTHING;
  PERFORM set_config('loyaltyhub.audit_chain', '', true);
  PERFORM 1 FROM audit_chain_head h WHERE h.service = 'audit.redaction' FOR UPDATE;
  RETURN true;
END
$$;

-- Il testo contiene il valore come parola intera, in forma NFC: prima e dopo non c'è una lettera o una cifra
-- ([:alnum:] del database), le stesse regole di PersonalTextScrubber (che passa i valori così come li ha trovati nella
-- voce). Un valore di meno di 3 caratteri non conta.
CREATE OR REPLACE FUNCTION audit_mentions(p_text text, p_value text) RETURNS boolean
  LANGUAGE sql IMMUTABLE SET search_path FROM CURRENT AS
$$
  SELECT p_text IS NOT NULL AND p_value IS NOT NULL AND length(btrim(p_value)) >= 3
     AND normalize(p_text, NFC) ~ ('(^|[^[:alnum:]])'
         || regexp_replace(normalize(btrim(p_value), NFC), '([^[:alnum:][:space:]])', '\\\1', 'g')
         || '($|[^[:alnum:]])')
$$;

-- Anonimizzazione di una voce per conto di un membro (MemberRedactionRepository, F-MBR-05). Condizioni: il fatto di
-- anonimizzazione `p_event_id` è nell'event store come fatto del membro (lo stato ANONYMIZED lo ha già verificato
-- insight all'ingest, prima di ripulire le copie, che possono riscrivere il payload); la voce riguarda il membro (è sua,
-- oppure la sintesi o un valore testuale di `before`/`after` ne cita l'id o uno dei valori personali `p_tokens`, come
-- parola intera: `audit_mentions`). La prova REDACT porta membro, fatto e correlazione. Sono condizioni contro gli
-- errori, non contro chi ha le credenziali applicative (Q-401).
CREATE OR REPLACE FUNCTION audit_redact(p_member_id text, p_event_id text, p_id text, p_summary text, p_before jsonb,
                                        p_after jsonb, p_tokens jsonb DEFAULT '[]'::jsonb,
                                        p_correlation_id text DEFAULT NULL)
  RETURNS boolean
  LANGUAGE plpgsql SET search_path FROM CURRENT AS
$$
DECLARE
  target_entity text;
  n             integer;
BEGIN
  IF coalesce(btrim(p_member_id), '') = '' OR coalesce(btrim(p_event_id), '') = '' THEN
    RAISE EXCEPTION 'audit_redact: membro e fatto di anonimizzazione obbligatori' USING ERRCODE = 'insufficient_privilege';
  END IF;
  PERFORM audit_redaction_lock();
  IF NOT EXISTS (
      SELECT 1 FROM event_store e
       WHERE e.event_id = p_event_id AND e.member_id = p_member_id AND e.family = 'FACT'
         AND e.short_type IN ('member.status.changed', 'member.registered', 'member.updated')) THEN
    RAISE EXCEPTION 'audit_redact: % non è un fatto di member-service del membro %', p_event_id, p_member_id
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  SELECT a.entity_id INTO target_entity FROM audit_entry a WHERE a.id = p_id;
  IF NOT FOUND THEN
    RETURN false;
  END IF;
  IF target_entity IS DISTINCT FROM p_member_id AND NOT EXISTS (
      SELECT 1
        FROM audit_entry a
        CROSS JOIN LATERAL (
              SELECT a.summary AS s
              UNION ALL
              SELECT v #>> '{}' FROM jsonb_path_query(coalesce(a.before, 'null'::jsonb),
                                                     'lax $.** ? (@.type() == "string")') v
              UNION ALL
              SELECT v #>> '{}' FROM jsonb_path_query(coalesce(a.after, 'null'::jsonb),
                                                     'lax $.** ? (@.type() == "string")') v) texts
        CROSS JOIN LATERAL (
              SELECT p_member_id AS w
              UNION ALL
              SELECT t.v FROM jsonb_array_elements_text(coalesce(p_tokens, '[]'::jsonb)) t(v)) words
       WHERE a.id = p_id AND audit_mentions(texts.s, words.w)) THEN
    RAISE EXCEPTION 'audit_redact: la voce % non riguarda il membro %', p_id, p_member_id
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  PERFORM set_config('loyaltyhub.audit_write', 'redact', true);
  PERFORM set_config('loyaltyhub.audit_redact_member', p_member_id, true);
  PERFORM set_config('loyaltyhub.audit_redact_event', p_event_id, true);
  PERFORM set_config('loyaltyhub.audit_redact_correlation', coalesce(p_correlation_id, ''), true);
  UPDATE audit_entry SET summary = p_summary, before = p_before, after = p_after WHERE id = p_id;
  GET DIAGNOSTICS n = ROW_COUNT;
  PERFORM set_config('loyaltyhub.audit_redact_correlation', '', true);
  PERFORM set_config('loyaltyhub.audit_redact_event', '', true);
  PERFORM set_config('loyaltyhub.audit_redact_member', '', true);
  PERFORM set_config('loyaltyhub.audit_write', '', true);
  RETURN n > 0;
END
$$;

-- Retention (RetentionJob): per ogni catena cancella la parte iniziale scaduta, cioè le voci fino alla prima con
-- `at >= soglia` esclusa. La soglia non va mai oltre `now() - audit_min_retention_days()`. Una voce scaduta che segue
-- una più recente resta finché non scade anche quella (la catena non si buca: SPEC-GAP Q-402). Restituisce le ancore
-- PURGE lasciate dal trigger, che il job scrive anche nei log.
CREATE OR REPLACE FUNCTION audit_purge_before(p_threshold timestamptz)
  RETURNS TABLE (anchor_service text, anchor_seq bigint, anchor_hash text, anchor_at timestamptz, purged_rows integer)
  LANGUAGE plpgsql SET search_path FROM CURRENT AS
$$
DECLARE
  svc        record;
  threshold  timestamptz := least(p_threshold, now() - make_interval(days => audit_min_retention_days()));
  keep_from  bigint;
  last_seq   bigint;
  n          integer;
BEGIN
  PERFORM set_config('loyaltyhub.audit_write', 'purge', true);
  FOR svc IN SELECT DISTINCT e.service FROM audit_entry e WHERE e.at < threshold ORDER BY 1 LOOP
    SELECT min(e.seq) INTO keep_from FROM audit_entry e WHERE e.service = svc.service AND e.at >= threshold;
    SELECT max(e.seq) INTO last_seq FROM audit_entry e
     WHERE e.service = svc.service AND (keep_from IS NULL OR e.seq < keep_from);
    CONTINUE WHEN last_seq IS NULL;
    DELETE FROM audit_entry e WHERE e.service = svc.service AND e.seq <= last_seq;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY SELECT a.service, a.seq, a.entry_hash, a.anchored_at, n FROM audit_anchor a
                  WHERE a.service = svc.service AND a.seq = last_seq AND a.kind = 'PURGE';
  END LOOP;
  PERFORM set_config('loyaltyhub.audit_write', '', true);
END
$$;

-- Reset della demo (InsightReset). Nel profilo demo la chiama solo il reset; con le credenziali applicative è
-- invocabile in ogni profilo finché la contract di Q-403 non la toglie al ruolo applicativo: una verifica completa vuota
-- va confrontata con le ancore nei log. La demo riparte dalla genesi, teste e ancore comprese.
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
