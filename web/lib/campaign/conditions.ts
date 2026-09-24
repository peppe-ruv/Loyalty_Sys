import type { Comparator } from "@/lib/segments/types";
import { COMPARATOR_LABEL } from "@/lib/segments/criteria";
import type { ConditionNode } from "./describe";

// Costruttore delle condizioni delle campagne (docs/08 §BO-06 sezione "4 Se", docs/03 §3.3). Logica pura, testata a
// parte: catalogo dei campi per spazio (`data`, `member`, `context`, `history`) con l'intersezione dei `data.*` fra più
// trigger, comparatori ammessi per tipo, albero dell'editor ⇄ JSON, limite di 3 livelli, conversione dei valori.

export type { Comparator };

export const ALL_COMPARATORS: Comparator[] = [
  "eq",
  "neq",
  "gt",
  "gte",
  "lt",
  "lte",
  "in",
  "nin",
  "contains",
  "ncontains",
  "exists",
  "nexists",
  "between",
  "startsWith",
];

/** Profondità massima dei gruppi (docs/08 §BO-06): il gruppo radice è il livello 1. */
export const MAX_DEPTH = 3;

export type GroupOp = "all" | "any" | "not";

export const GROUP_LABEL: Record<GroupOp, string> = { all: "TUTTE", any: "ALMENO UNA", not: "NESSUNA" };

// ---------- tipi dei campi e catalogo ----------

/** Tipo del valore di un campo nel costruttore. `unknown` = percorso scritto a mano, fuori catalogo. */
export type ValueType = "number" | "string" | "enum" | "boolean" | "list" | "date" | "object" | "unknown";

export type FieldSpace = "data" | "member" | "context" | "history";

export const SPACES: { id: FieldSpace; label: string }[] = [
  { id: "data", label: "Dati dell'azione" },
  { id: "member", label: "Membro" },
  { id: "context", label: "Contesto" },
  { id: "history", label: "Storico" },
];

export interface ConditionField {
  /** Percorso completo, es. `data.amount`, `member.attributes.householdSize`. */
  path: string;
  label: string;
  space: FieldSpace;
  type: ValueType;
  /** Valori ammessi (enum; per `list` le opzioni suggerite, es. i codici dei segmenti). */
  options?: string[];
  /** Etichette leggibili delle opzioni (es. nome del segmento). */
  optionLabels?: Record<string, string>;
  /** Enum su un attributo numerico: i valori vanno salvati come numeri. */
  numericOptions?: boolean;
  hint?: string;
  /** Campo non presente nel catalogo (percorso scritto a mano o JSON esistente). */
  custom?: boolean;
}

/** Riga di `GET ingestion /v1/event-types/{code}/fields` (docs/servizi/ingestion-service.md). */
export interface EventTypeField {
  path: string;
  type: "number" | "integer" | "string" | "boolean" | "array" | "object";
  required?: boolean;
  enum?: string[] | null;
  format?: "date" | "date-time" | string | null;
}

/** Riga di `GET member /v1/attribute-definitions` (docs/servizi/member-service.md). */
export interface AttributeDefinition {
  key: string;
  label: string;
  type: "STRING" | "NUMBER" | "BOOLEAN" | "DATE";
  options: string[] | null;
}

/** Comparatori coerenti col tipo del campo; il primo è quello proposto di default. */
export const COMPARATORS_BY_TYPE: Record<ValueType, Comparator[]> = {
  number: ["gte", "gt", "lte", "lt", "eq", "neq", "between", "in", "nin", "exists", "nexists"],
  string: ["eq", "neq", "in", "nin", "startsWith", "contains", "ncontains", "exists", "nexists"],
  enum: ["eq", "neq", "in", "nin"],
  boolean: ["eq", "exists", "nexists"],
  list: ["contains", "ncontains", "exists", "nexists"],
  // SPEC-GAP: Q-91 — il motore (ConditionEvaluator) confronta gt/gte/lt/lte/between solo sui numeri: sulle date ISO
  // oggi solo `eq` funziona. Offriamo comunque i comparatori della spec; il confronto lessicografico va fatto nel motore.
  date: ["gte", "gt", "lte", "lt", "eq", "between"],
  object: ["exists", "nexists"],
  unknown: ALL_COMPARATORS,
};

export function comparatorsFor(type: ValueType): Comparator[] {
  return COMPARATORS_BY_TYPE[type];
}

const DATE_CMP_LABEL: Partial<Record<Comparator, string>> = {
  eq: "è il",
  gt: "dopo il",
  gte: "dal",
  lt: "prima del",
  lte: "fino al",
  between: "tra",
};

/** Etichetta italiana del comparatore (per le date: "dal", "fino al"…). */
export function comparatorLabel(cmp: Comparator, type?: ValueType): string {
  if (type === "date" && DATE_CMP_LABEL[cmp]) return DATE_CMP_LABEL[cmp]!;
  return COMPARATOR_LABEL[cmp] ?? cmp;
}

export const TIERS_FALLBACK = ["BASE", "SILVER", "GOLD", "PLATINUM"];
export const MEMBER_STATUSES = ["ACTIVE", "INACTIVE", "BLOCKED"];
export const DAYS_OF_WEEK = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"];
const DAY_LABEL: Record<string, string> = {
  MON: "lunedì",
  TUE: "martedì",
  WED: "mercoledì",
  THU: "giovedì",
  FRI: "venerdì",
  SAT: "sabato",
  SUN: "domenica",
};

/** Tipo del costruttore per un campo `data.*` dichiarato dallo schema del tipo azione. */
export function dataFieldType(f: Pick<EventTypeField, "type" | "enum" | "format">): ValueType {
  switch (f.type) {
    case "number":
    case "integer":
      return "number";
    case "boolean":
      return "boolean";
    case "array":
      return "list";
    case "object":
      return "object";
    default:
      if (f.enum && f.enum.length > 0) return "enum";
      if (f.format === "date" || f.format === "date-time") return "date";
      return "string";
  }
}

/**
 * Campi `data.*` comuni a tutti i trigger scelti (intersezione per percorso, docs/08 §BO-06). I trigger di cui i campi
 * non sono ancora arrivati sono ignorati. Tipi diversi fra trigger: `number`/`integer` → `number`, altrimenti `string`;
 * enum → unione dei valori (se tutti gli schemi ne dichiarano uno); `required` solo se lo è ovunque.
 */
export function commonDataFields(
  fieldsByTrigger: Record<string, EventTypeField[] | undefined>,
  triggers: string[],
): EventTypeField[] {
  const lists = triggers.map((t) => fieldsByTrigger[t]).filter((l): l is EventTypeField[] => Array.isArray(l));
  if (lists.length === 0) return [];
  const [first, ...rest] = lists;
  const out: EventTypeField[] = [];
  for (const f of first) {
    const others = rest.map((l) => l.find((x) => x.path === f.path));
    if (others.some((o) => o === undefined)) continue;
    const all = [f, ...(others as EventTypeField[])];
    const numeric = (t: string) => t === "number" || t === "integer";
    const type = all.every((x) => x.type === f.type)
      ? f.type
      : all.every((x) => numeric(x.type))
        ? "number"
        : "string";
    const merged: EventTypeField = { path: f.path, type, required: all.every((x) => x.required === true) };
    if (type === "string" && all.every((x) => x.enum && x.enum.length > 0)) {
      merged.enum = [...new Set(all.flatMap((x) => x.enum ?? []))];
    }
    if (all.every((x) => x.format && x.format === f.format)) merged.format = f.format;
    out.push(merged);
  }
  return out;
}

function dataLabel(path: string): string {
  const rest = path.replace(/^data\./, "");
  return rest.replace(/\[\*\]\./g, " › ").replace(/\[\*\]$/, "");
}

export interface CatalogInput {
  /** Campi `data.*` già intersecati (vedi {@link commonDataFields}). */
  dataFields?: EventTypeField[];
  /** Codici dei tier (da `wallet GET /v1/tiers`); assenti → BASE/SILVER/GOLD/PLATINUM. */
  tiers?: string[];
  /** Segmenti (da `member GET /v1/segments`): il valore è il codice. */
  segments?: { code: string; name: string }[];
  attributes?: AttributeDefinition[];
}

/**
 * Catalogo dei campi del costruttore, nell'ordine degli spazi.
 * SPEC-GAP: Q-92 — docs/08 §BO-06 prevede `campaign GET /v1/meta/condition-fields` per member/context/history, che
 * non esiste ancora: quei campi sono fissi qui (docs/03 §3.3), gli attributi custom vengono da member.
 */
export function buildCatalog(input: CatalogInput): ConditionField[] {
  const out: ConditionField[] = [];
  for (const f of input.dataFields ?? []) {
    const type = dataFieldType(f);
    out.push({
      path: f.path,
      label: dataLabel(f.path),
      space: "data",
      type,
      ...(type === "enum" ? { options: f.enum ?? [] } : {}),
      ...(f.path.includes("[*]") ? { hint: "vero se almeno un elemento soddisfa" } : {}),
    });
  }
  const tiers = input.tiers && input.tiers.length > 0 ? input.tiers : TIERS_FALLBACK;
  out.push(
    { path: "member.tier", label: "Livello", space: "member", type: "enum", options: tiers },
    { path: "member.status", label: "Stato", space: "member", type: "enum", options: MEMBER_STATUSES },
    {
      path: "member.segments",
      label: "Segmenti",
      space: "member",
      type: "list",
      ...(input.segments && input.segments.length > 0
        ? {
            options: input.segments.map((s) => s.code),
            optionLabels: Object.fromEntries(input.segments.map((s) => [s.code, s.name])),
          }
        : { hint: "codice del segmento, es. SEG-…" }),
    },
    { path: "member.labels", label: "Etichette", space: "member", type: "list", hint: "es. ebill" },
    { path: "member.registeredDaysAgo", label: "Giorni dall'iscrizione", space: "member", type: "number" },
    { path: "member.age", label: "Età (anni)", space: "member", type: "number" },
  );
  for (const a of input.attributes ?? []) out.push(attributeField(a));
  out.push(
    // context.source è l'attributo source dell'azione: sempre l'URN della fonte (docs/05 §2), anche in simulazione.
    { path: "context.source", label: "Fonte", space: "context", type: "string", hint: "URN della fonte, es. urn:loyaltyhub:source:ecommerce" },
    {
      path: "context.dayOfWeek",
      label: "Giorno della settimana",
      space: "context",
      type: "enum",
      options: DAYS_OF_WEEK,
      optionLabels: DAY_LABEL,
    },
    { path: "context.hour", label: "Ora (0–23)", space: "context", type: "number", hint: "ora di Roma" },
    { path: "context.date", label: "Data dell'azione", space: "context", type: "date" },
    { path: "history.actionCount", label: "Azioni precedenti dello stesso tipo", space: "history", type: "number" },
    {
      path: "history.daysSinceLastAction",
      label: "Giorni dall'ultima azione dello stesso tipo",
      space: "history",
      type: "number",
    },
  );
  return out;
}

/** Attributo custom del membro → campo `member.attributes.<key>` (con opzioni ⇒ enum). */
export function attributeField(a: AttributeDefinition): ConditionField {
  const base = { path: `member.attributes.${a.key}`, label: a.label || a.key, space: "member" as const };
  const options = a.options && a.options.length > 0 ? a.options : null;
  switch (a.type) {
    case "BOOLEAN":
      return { ...base, type: "boolean" };
    case "DATE":
      return { ...base, type: "date" };
    case "NUMBER":
      return options ? { ...base, type: "enum", options, numericOptions: true } : { ...base, type: "number" };
    default:
      return options ? { ...base, type: "enum", options } : { ...base, type: "string" };
  }
}

function spaceOf(path: string): FieldSpace | null {
  const s = path.split(".")[0];
  return s === "data" || s === "member" || s === "context" || s === "history" ? s : null;
}

/** Campo del catalogo per un percorso; fuori catalogo → campo `unknown` (tutti i comparatori), mai scartato. */
export function lookupField(catalog: ConditionField[], path: string): ConditionField {
  const found = catalog.find((f) => f.path === path);
  if (found) return found;
  return { path, label: path, space: spaceOf(path) ?? "data", type: "unknown", custom: true };
}

/** Campi del catalogo raggruppati per spazio, filtrati da un testo (etichetta o percorso). */
export function groupFields(catalog: ConditionField[], query = ""): { space: FieldSpace; label: string; fields: ConditionField[] }[] {
  const q = query.trim().toLowerCase();
  return SPACES.map((s) => ({
    space: s.id,
    label: s.label,
    fields: catalog.filter(
      (f) => f.space === s.id && (!q || f.label.toLowerCase().includes(q) || f.path.toLowerCase().includes(q)),
    ),
  })).filter((g) => g.fields.length > 0);
}

/** Un percorso scritto a mano è plausibile se inizia con uno spazio noto e ha almeno un segmento. */
export function isPlausiblePath(path: string): boolean {
  return /^(data|member|context|history)\.[A-Za-z0-9_$[\]*.-]+$/.test(path.trim()) && !path.trim().endsWith(".");
}

// ---------- albero dell'editor ----------

export interface UiLeaf {
  kind: "leaf";
  id: string;
  field: string;
  cmp: Comparator;
  value?: unknown;
}

export interface UiGroup {
  kind: "group";
  id: string;
  op: GroupOp;
  rules: UiNode[];
}

export type UiNode = UiLeaf | UiGroup;

let seq = 0;
/** Identificativo locale di un nodo (chiave React, mappa degli errori); non finisce nel JSON. */
export function nodeId(): string {
  seq += 1;
  return `c${seq}`;
}

export function emptyGroup(op: GroupOp = "all"): UiGroup {
  return { kind: "group", id: nodeId(), op, rules: [] };
}

export function needsValue(cmp: Comparator): boolean {
  return cmp !== "exists" && cmp !== "nexists";
}

export type ValueShape = "none" | "scalar" | "list" | "range";

export function valueShape(cmp: Comparator): ValueShape {
  if (!needsValue(cmp)) return "none";
  if (cmp === "in" || cmp === "nin") return "list";
  if (cmp === "between") return "range";
  return "scalar";
}

/** Valore iniziale per campo e comparatore (booleani: `true`; elenchi: `[]`; intervalli: `[null, null]`). */
export function defaultValue(field: ConditionField, cmp: Comparator): unknown {
  switch (valueShape(cmp)) {
    case "none":
      return undefined;
    case "list":
      return [];
    case "range":
      return [null, null];
    default:
      return field.type === "boolean" ? true : "";
  }
}

export function newLeaf(field: ConditionField): UiLeaf {
  const cmp = comparatorsFor(field.type)[0];
  return { kind: "leaf", id: nodeId(), field: field.path, cmp, value: defaultValue(field, cmp) };
}

/** Cambio di campo: tiene il comparatore se ammesso e il valore se il tipo (e le opzioni) non cambiano. */
export function changeField(leaf: UiLeaf, prev: ConditionField, next: ConditionField): UiLeaf {
  const allowed = comparatorsFor(next.type);
  // Verso un percorso libero (tutti i comparatori ammessi) si riparte da `eq`, non dall'operatore del tipo precedente.
  const cmp = allowed.includes(leaf.cmp) && next.type !== "unknown" ? leaf.cmp : allowed[0];
  const sameType =
    prev.type === next.type &&
    prev.type !== "unknown" &&
    JSON.stringify(prev.options ?? null) === JSON.stringify(next.options ?? null) &&
    cmp === leaf.cmp;
  return { ...leaf, field: next.path, cmp, value: sameType ? leaf.value : defaultValue(next, cmp) };
}

const isBlank = (v: unknown) => v === undefined || v === null || v === "";

/** Cambio di comparatore: adatta la forma del valore (scalare ⇄ elenco ⇄ intervallo) senza perdere il dato. */
export function changeComparator(leaf: UiLeaf, cmp: Comparator, field: ConditionField): UiLeaf {
  const prev = leaf.value;
  let value: unknown;
  switch (valueShape(cmp)) {
    case "none":
      value = undefined;
      break;
    case "list":
      value = Array.isArray(prev) ? prev.filter((x) => !isBlank(x)) : isBlank(prev) ? [] : [prev];
      break;
    case "range":
      value =
        Array.isArray(prev) && prev.length === 2
          ? prev
          : [Array.isArray(prev) ? (prev[0] ?? null) : isBlank(prev) || typeof prev === "boolean" ? null : prev, null];
      break;
    default:
      value = Array.isArray(prev) ? (prev.find((x) => !isBlank(x)) ?? "") : prev === undefined ? defaultValue(field, cmp) : prev;
  }
  const out: UiLeaf = { ...leaf, cmp, value };
  if (value === undefined) delete out.value;
  return out;
}

function toNumber(raw: string): number | null {
  const t = raw.trim().replace(",", ".");
  if (t === "") return null;
  const n = Number(t);
  return Number.isFinite(n) ? n : null;
}

/** Testo digitato → valore JSON del tipo del campo (numeri come numeri; testo non numerico resta testo). */
export function coerceScalar(raw: string, field: Pick<ConditionField, "type" | "numericOptions">): unknown {
  if (field.type === "number" || (field.type === "enum" && field.numericOptions)) {
    const n = toNumber(raw);
    return n === null ? raw.trim() : n;
  }
  if (field.type === "boolean") {
    if (raw === "true") return true;
    if (raw === "false") return false;
    return raw;
  }
  if (field.type === "unknown") {
    const t = raw.trim();
    if (t === "true" || t === "false") return t === "true";
    const n = toNumber(t);
    return n !== null && /^-?\d+([.,]\d+)?$/.test(t) ? n : raw;
  }
  return raw;
}

/** Elenco separato da virgole → valori del tipo del campo, senza vuoti né doppioni. */
export function parseList(raw: string, field: Pick<ConditionField, "type" | "numericOptions">): unknown[] {
  const items = raw
    .split(/[,;\n]/)
    .map((x) => x.trim())
    .filter(Boolean)
    .map((x) => coerceScalar(x, field));
  return items.filter((x, i) => items.findIndex((y) => y === x) === i);
}

/** Valore → testo da mostrare in un campo di input. */
export function valueText(v: unknown): string {
  if (v === undefined || v === null) return "";
  if (Array.isArray(v)) return v.map(valueText).join(", ");
  return String(v);
}

// ---------- JSON ⇄ albero ----------

type ParseResult = { tree: UiGroup; error: null } | { tree: null; error: string };

function isObject(x: unknown): x is Record<string, unknown> {
  return typeof x === "object" && x !== null && !Array.isArray(x);
}

function parseNode(raw: unknown, where: string): UiNode {
  if (!isObject(raw)) throw new Error(`${where}: serve un oggetto`);
  if ("op" in raw || "rules" in raw) {
    const op = raw.op ?? "all";
    if (op !== "all" && op !== "any" && op !== "not") throw new Error(`${where}: operatore di gruppo sconosciuto «${String(op)}»`);
    const rules = raw.rules ?? [];
    if (!Array.isArray(rules)) throw new Error(`${where}: "rules" deve essere un elenco`);
    return { kind: "group", id: nodeId(), op, rules: rules.map((r, i) => parseNode(r, `${where}.rules[${i}]`)) };
  }
  if ("field" in raw) {
    if (typeof raw.field !== "string") throw new Error(`${where}: "field" deve essere un testo`);
    const cmp = raw.cmp ?? "eq";
    if (typeof cmp !== "string" || !ALL_COMPARATORS.includes(cmp as Comparator)) {
      throw new Error(`${where}: comparatore sconosciuto «${String(cmp)}»`);
    }
    const leaf: UiLeaf = { kind: "leaf", id: nodeId(), field: raw.field, cmp: cmp as Comparator };
    if ("value" in raw && raw.value !== undefined) leaf.value = raw.value;
    return leaf;
  }
  if (Object.keys(raw).length === 0) return emptyGroup();
  throw new Error(`${where}: serve {op, rules} oppure {field, cmp, value}`);
}

/** Livelli di gruppi di un nodo (una foglia vale 0, un gruppo senza sottogruppi 1). */
export function groupDepth(node: UiNode | ConditionNode): number {
  if ("kind" in node ? node.kind === "leaf" : !("op" in node)) return 0;
  const rules = (node as { rules?: (UiNode | ConditionNode)[] }).rules ?? [];
  return 1 + rules.reduce((m, r) => Math.max(m, groupDepth(r)), 0);
}

/**
 * JSON delle condizioni → albero dell'editor. `null`/`{}` → gruppo TUTTE vuoto; una foglia sola viene avvolta in un
 * gruppo TUTTE. Errore (e albero `null`) su struttura non valida o più di {@link MAX_DEPTH} livelli.
 */
export function fromJson(raw: unknown): ParseResult {
  if (raw === null || raw === undefined) return { tree: emptyGroup(), error: null };
  try {
    const node = parseNode(raw, "condizioni");
    const tree: UiGroup = node.kind === "group" ? node : { kind: "group", id: nodeId(), op: "all", rules: [node] };
    if (groupDepth(tree) > MAX_DEPTH) return { tree: null, error: `Al massimo ${MAX_DEPTH} livelli di gruppi` };
    return { tree, error: null };
  } catch (e) {
    return { tree: null, error: (e as Error).message };
  }
}

/** Testo della vista JSON → albero, o messaggio d'errore (l'albero precedente resta intatto). */
export function parseConditionsText(text: string): ParseResult {
  if (!text.trim()) return { tree: emptyGroup(), error: null };
  let raw: unknown;
  try {
    raw = JSON.parse(text);
  } catch (e) {
    return { tree: null, error: `JSON non valido: ${(e as Error).message}` };
  }
  return fromJson(raw);
}

function nodeToJson(node: UiNode): ConditionNode | null {
  if (node.kind === "leaf") {
    const leaf: { field: string; cmp: string; value?: unknown } = { field: node.field, cmp: node.cmp };
    if (needsValue(node.cmp) && node.value !== undefined) leaf.value = node.value;
    return leaf;
  }
  const rules = node.rules.map(nodeToJson).filter((r): r is ConditionNode => r !== null);
  if (rules.length === 0) return null;
  return { op: node.op, rules };
}

/**
 * Albero → JSON come lo salva il servizio. I gruppi vuoti sono tolti (un NESSUNA vuoto renderebbe falsa la campagna);
 * nessuna condizione → `null` (il servizio usa `{op: all, rules: []}`, sempre vero).
 */
export function toJson(tree: UiGroup): ConditionNode | null {
  return nodeToJson(tree);
}

/** JSON indentato per la vista JSON (vuoto → gruppo TUTTE senza regole, comodo da completare). */
export function toJsonText(tree: UiGroup): string {
  return JSON.stringify(toJson(tree) ?? { op: "all", rules: [] }, null, 2);
}

// ---------- operazioni sull'albero (immutabili) ----------

function mapGroup(group: UiGroup, fn: (n: UiNode) => UiNode | null): UiGroup {
  const rules: UiNode[] = [];
  for (const r of group.rules) {
    const mapped = fn(r);
    if (mapped === null) continue;
    rules.push(mapped.kind === "group" ? mapGroup(mapped, fn) : mapped);
  }
  return { ...group, rules };
}

/** Sostituisce il nodo `id` (radice compresa) con `fn(nodo)`. */
export function updateNode(tree: UiGroup, id: string, fn: (n: UiNode) => UiNode): UiGroup {
  if (tree.id === id) return fn(tree) as UiGroup;
  return mapGroup(tree, (n) => (n.id === id ? fn(n) : n));
}

/** Toglie il nodo `id` (la radice non si toglie). */
export function removeNode(tree: UiGroup, id: string): UiGroup {
  return mapGroup(tree, (n) => (n.id === id ? null : n));
}

/** Aggiunge un figlio al gruppo `groupId`; un sottogruppo oltre {@link MAX_DEPTH} livelli è rifiutato (albero invariato). */
export function appendChild(tree: UiGroup, groupId: string, child: UiNode): UiGroup {
  if (child.kind === "group" && levelOf(tree, groupId) + groupDepth(child) > MAX_DEPTH) return tree;
  return updateNode(tree, groupId, (g) => (g.kind === "group" ? { ...g, rules: [...g.rules, child] } : g));
}

/** Livello del gruppo `id` (radice = 1); 0 se non trovato. */
export function levelOf(tree: UiGroup, id: string, level = 1): number {
  if (tree.id === id) return level;
  for (const r of tree.rules) {
    if (r.kind === "group") {
      const l = levelOf(r, id, level + 1);
      if (l > 0) return l;
    }
  }
  return 0;
}

export function canAddGroup(tree: UiGroup, groupId: string): boolean {
  const l = levelOf(tree, groupId);
  return l > 0 && l < MAX_DEPTH;
}

export function leaves(node: UiNode): UiLeaf[] {
  return node.kind === "leaf" ? [node] : node.rules.flatMap(leaves);
}

export function countLeaves(node: UiNode): number {
  return leaves(node).length;
}

// ---------- validazione e avvisi ----------

const DATE_RE = /^\d{4}-\d{2}-\d{2}/;

function scalarProblem(v: unknown, field: ConditionField): string | null {
  if (isBlank(v)) return "Valore mancante";
  switch (field.type) {
    case "number":
      return typeof v === "number" ? null : "Serve un numero";
    case "boolean":
      return typeof v === "boolean" ? null : "Scegli sì o no";
    case "date":
      return typeof v === "string" && DATE_RE.test(v) ? null : "Data non valida (AAAA-MM-GG)";
    case "enum":
      return field.options && !field.options.map(String).includes(String(v)) ? `Valore non ammesso: ${String(v)}` : null;
    default:
      return null;
  }
}

/** Problema di una foglia (per evidenziarla e bloccare il salvataggio), o `null`. */
export function leafProblem(leaf: UiLeaf, field: ConditionField): string | null {
  if (!leaf.field.trim()) return "Scegli un campo";
  if (!comparatorsFor(field.type).includes(leaf.cmp)) return "Operatore non ammesso per questo campo";
  const v = leaf.value;
  switch (valueShape(leaf.cmp)) {
    case "none":
      return null;
    case "list": {
      if (!Array.isArray(v) || v.length === 0) return "Aggiungi almeno un valore";
      for (const x of v) {
        const p = scalarProblem(x, field);
        if (p) return p;
      }
      return null;
    }
    case "range": {
      if (!Array.isArray(v) || v.length !== 2 || isBlank(v[0]) || isBlank(v[1])) return "Servono due valori: da, a";
      const p = scalarProblem(v[0], field) ?? scalarProblem(v[1], field);
      if (p) return p;
      if (field.type === "unknown" && (typeof v[0] !== "number" || typeof v[1] !== "number")) return "Servono due numeri";
      if ((typeof v[0] === "number" && typeof v[1] === "number" && v[0] > v[1]) || (typeof v[0] === "string" && typeof v[1] === "string" && v[0] > v[1])) {
        return "Il primo valore supera il secondo";
      }
      return null;
    }
    default:
      if (field.type === "list" && isBlank(v)) return "Valore mancante";
      return scalarProblem(v, field);
  }
}

/** Problemi di tutte le foglie: id del nodo → messaggio. */
export function validateTree(tree: UiGroup, catalog: ConditionField[]): Record<string, string> {
  const out: Record<string, string> = {};
  for (const leaf of leaves(tree)) {
    const p = leafProblem(leaf, lookupField(catalog, leaf.field));
    if (p) out[leaf.id] = p;
  }
  return out;
}

export interface WarningInput {
  triggers: string[];
  fieldsByTrigger: Record<string, EventTypeField[] | undefined>;
  catalog: ConditionField[];
  /** Spazi il cui catalogo non è arrivato (servizio che dorme): niente avvisi "fuori catalogo" lì. */
  unavailable?: ("data" | "member")[];
}

/**
 * Avvisi non bloccanti (docs/08 §BO-06): un campo `data.*` non comune a tutti i trigger (la foglia sarebbe falsa per
 * gli altri) o un campo fuori catalogo. Il campo non viene mai tolto.
 */
export function fieldWarnings(tree: UiGroup, input: WarningInput): Record<string, string> {
  const out: Record<string, string> = {};
  const loaded = input.triggers.length > 0 && input.triggers.every((t) => Array.isArray(input.fieldsByTrigger[t]));
  const common = new Set(commonDataFields(input.fieldsByTrigger, input.triggers).map((f) => f.path));
  for (const leaf of leaves(tree)) {
    const path = leaf.field.trim();
    if (!path) continue;
    if (path.startsWith("data.")) {
      if (!loaded || common.has(path)) continue;
      const present = input.triggers.filter((t) => input.fieldsByTrigger[t]!.some((f) => f.path === path));
      if (present.length === 0) {
        out[leaf.id] = "Campo non dichiarato dai trigger scelti: la condizione potrebbe non essere mai vera";
      } else {
        const missing = input.triggers.filter((t) => !present.includes(t));
        out[leaf.id] = `Campo non comune a tutti i trigger: manca in ${missing.join(", ")} (lì la condizione è falsa)`;
      }
      continue;
    }
    const space = spaceOf(path);
    if (space === "member" && input.unavailable?.includes("member") && path.startsWith("member.attributes.")) continue;
    if (!input.catalog.some((f) => f.path === path)) {
      out[leaf.id] = space ? "Campo fuori catalogo: controlla il percorso" : "Spazio sconosciuto: usa data., member., context. o history.";
    }
  }
  return out;
}
