// Fonti e azioni ammesse (BO-09 schede Azioni e Fonti, BO-06 «2 · Quando»; F-ING-05). Puro e testato.
// Regola del servizio (ingestion §3, Q-270): una fonte con `allowedTypes` vuoto accetta **tutte** le azioni; con un
// elenco accetta solo quelle, le altre diventano `REJECTED/TYPE_NOT_ALLOWED`. `PUT /v1/sources/{code}` sostituisce
// l'elenco intero: la UI manda sempre l'elenco completo e non trasforma mai un elenco pieno in `[]` senza una scelta
// esplicita. SPEC-GAP: Q-433.

export interface SourceRow {
  code: string;
  name: string;
  kind: string;
  enabled: boolean;
  allowedTypes: string[];
  description: string | null;
}

export type SourceStatus = "ACCEPTS" | "ALL" | "NOT_ALLOWED" | "OFF";

export interface SourceReach {
  source: SourceRow;
  status: SourceStatus;
}

/** Accetta l'azione, a prescindere dall'essere accesa. */
export function sourceAccepts(source: Pick<SourceRow, "allowedTypes">, code: string): boolean {
  return source.allowedTypes.length === 0 || source.allowedTypes.includes(code);
}

/** «Da dove può arrivare»: una riga per fonte, prima quelle esterne (HTTP), poi le interne. */
export function reachOf(code: string, sources: SourceRow[]): SourceReach[] {
  const rank = (s: SourceRow) => (s.kind === "HTTP" ? 0 : 1);
  return [...sources]
    .sort((a, b) => rank(a) - rank(b) || a.name.localeCompare(b.name))
    .map((source) => ({
      source,
      status: !source.enabled
        ? "OFF"
        : source.allowedTypes.length === 0
          ? "ALL"
          : source.allowedTypes.includes(code)
            ? "ACCEPTS"
            : "NOT_ALLOWED",
    }));
}

/** Stato mostrato in «Da dove può arrivare»: come `reachOf`, più `PROGRAM` per un'azione generata dal programma. */
export type ReachStatus = SourceStatus | "PROGRAM";

/**
 * «Da dove può arrivare» di un'azione: per un'azione generata dal programma le fonti esterne dicono `PROGRAM` (non la
 * inviano e non si abilitano), le interne restano come sono.
 */
export function reachFor(type: { code: string; category: string | null }, sources: SourceRow[]): { source: SourceRow; status: ReachStatus }[] {
  const program = isProgramGenerated(type);
  return reachOf(type.code, sources).map(({ source, status }) => ({
    source,
    status: program && isEditableSource(source) ? "PROGRAM" : status,
  }));
}

/** Fonti esterne accese che accettano l'azione: vuoto = arriva solo dal simulatore (o dal ponte interno). */
export function externalSourcesFor(code: string, sources: SourceRow[]): SourceRow[] {
  return sources.filter((s) => s.kind === "HTTP" && s.enabled && sourceAccepts(s, code));
}

/** Elenco da inviare per **aggiungere** un'azione a una fonte: sempre l'elenco completo. `null` = niente da fare. */
export function withAllowedType(source: Pick<SourceRow, "allowedTypes">, code: string): string[] | null {
  if (source.allowedTypes.length === 0 || source.allowedTypes.includes(code)) return null;
  return [...source.allowedTypes, code];
}

/**
 * Elenco da inviare per **togliere** un'azione. Togliere l'ultima aprirebbe la fonte a tutte le azioni (`[]`): in quel
 * caso nessun elenco, e la UI chiede la scelta esplicita della scheda Fonti.
 */
export function withoutAllowedType(
  source: Pick<SourceRow, "allowedTypes">,
  code: string,
): { allowedTypes: string[] } | { blocked: "LAST_TYPE" | "ALL_TYPES" | "NOT_PRESENT" } {
  if (source.allowedTypes.length === 0) return { blocked: "ALL_TYPES" };
  if (!source.allowedTypes.includes(code)) return { blocked: "NOT_PRESENT" };
  if (source.allowedTypes.length === 1) return { blocked: "LAST_TYPE" };
  return { allowedTypes: source.allowedTypes.filter((t) => t !== code) };
}

export type AllowedMode = "LIST" | "ALL";

/**
 * Elenco da inviare dall'editor delle azioni ammesse della scheda Fonti. `ALL` è l'unico modo di ottenere `[]`;
 * `LIST` senza azioni scelte non si salva.
 */
export function planAllowedTypes(
  mode: AllowedMode,
  selected: Iterable<string>,
): { ok: true; allowedTypes: string[] } | { ok: false; reason: "EMPTY_LIST" } {
  if (mode === "ALL") return { ok: true, allowedTypes: [] };
  const list = [...new Set(selected)].filter(Boolean);
  if (list.length === 0) return { ok: false, reason: "EMPTY_LIST" };
  return { ok: true, allowedTypes: list };
}

/**
 * Fonti ammesse di una campagna che non accettano uno o più trigger scelti (Q-437), una voce per fonte. `none` = la
 * fonte non accetta nessun trigger: da lì la campagna non scatterà mai; altrimenti non scatterà solo per i trigger
 * elencati. Nessuna fonte ammessa = tutte, nessun avviso.
 */
export function unreachableTriggers(
  allowedSources: string[],
  triggers: string[],
  sources: SourceRow[],
): { source: SourceRow; triggers: string[]; none: boolean }[] {
  const out: { source: SourceRow; triggers: string[]; none: boolean }[] = [];
  for (const code of allowedSources) {
    const s = sources.find((x) => x.code === code);
    if (!s) continue;
    const refused = triggers.filter((t) => !sourceAccepts(s, t));
    if (refused.length > 0) out.push({ source: s, triggers: refused, none: refused.length === triggers.length });
  }
  return out;
}

/**
 * Azione generata dal programma (categoria `INTERNAL`: arriva dal ponte interno, M3.5). Non si abilita su una fonte
 * esterna: un sistema esterno potrebbe altrimenti inviare fatti come `referral.completed` o `tier.upgraded`.
 */
export function isProgramGenerated(type: { category: string | null }): boolean {
  return type.category === "INTERNAL";
}

/** Fonte modificabile dalla UI: solo le esterne. Il ponte interno e il simulatore accettano sempre tutte le azioni. */
export function isEditableSource(source: Pick<SourceRow, "kind">): boolean {
  return source.kind === "HTTP";
}

/**
 * Fonti su cui abilitare subito una nuova azione (sezione 5 dell'editor, Q-433). Vuoto se chi salva non ha
 * `program.config` (MARKETING non scrive mai sulle fonti, qualunque cosa sia stata spuntata), se l'azione non è nuova
 * o se è generata dal programma; solo fonti esterne che non la accettano già.
 */
export function sourcesToEnable(
  picked: string[],
  sources: SourceRow[],
  opts: { canConfigSources: boolean; isNew: boolean; code: string; category: string | null },
): SourceRow[] {
  if (!opts.canConfigSources || !opts.isNew || isProgramGenerated(opts)) return [];
  return sources.filter((s) => picked.includes(s.code) && isEditableSource(s) && withAllowedType(s, opts.code) != null);
}
