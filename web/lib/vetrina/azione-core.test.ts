// @vitest-environment node
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import {
  ACTION_FILE_NAME,
  ACTION_SCOPE,
  PROGRAM_SCOPE,
  parseActionAmount,
  ACTION_TYPES,
  Api,
  assertActionImport,
  assertAllowedRequest,
  buildActionRow,
  findTestMember,
  STORY_FILE_NAME,
  STORY_SOURCE_URN,
  type ProgramSeed,
} from "./programma-core.mjs";
import seedSnapshot from "./programma-seed.generated.json";
import { makeHub } from "@/test/vetrinaHub";

// V11 (F2-ING-02, BO-32, Q-675): riga dell'azione singola per tipo contro gli schemi di contracts/events/action/, lista
// bianca dell'import di una riga e ambito delle richieste.

const SEED = seedSnapshot as unknown as ProgramSeed;
const NOW = Date.parse("2026-10-02T12:00:00Z");
const UID = "0123456789abcdef0123456789abcdef";
const SCHEMAS = join(__dirname, "../../../contracts/events/action");

interface Schema {
  type?: string;
  enum?: unknown[];
  minimum?: number;
  exclusiveMinimum?: number;
  maximum?: number;
  minLength?: number;
  required?: string[];
  properties?: Record<string, Schema>;
}

/** Validatore minimo dei sottoinsiemi di JSON Schema usati dai contratti delle azioni (tipi, enum, limiti, required). */
function validate(schema: Schema, value: unknown, path = "data"): string[] {
  const errors: string[] = [];
  if (schema.type === "object" || schema.properties) {
    if (typeof value !== "object" || value === null) return [`${path}: atteso un oggetto`];
    const obj = value as Record<string, unknown>;
    for (const k of schema.required ?? []) if (!(k in obj)) errors.push(`${path}.${k}: obbligatorio`);
    for (const [k, v] of Object.entries(obj)) if (schema.properties?.[k]) errors.push(...validate(schema.properties[k], v, `${path}.${k}`));
    return errors;
  }
  if (schema.enum && !schema.enum.includes(value)) errors.push(`${path}: fuori dall'enum`);
  if (schema.type === "string" && (typeof value !== "string" || (schema.minLength ?? 0) > value.length)) errors.push(`${path}: stringa non valida`);
  if (schema.type === "integer" && !Number.isInteger(value)) errors.push(`${path}: intero atteso`);
  if (schema.type === "number" && typeof value !== "number") errors.push(`${path}: numero atteso`);
  if (typeof value === "number") {
    if (schema.minimum !== undefined && value < schema.minimum) errors.push(`${path}: sotto il minimo`);
    if (schema.exclusiveMinimum !== undefined && value <= schema.exclusiveMinimum) errors.push(`${path}: non sopra il minimo esclusivo`);
    if (schema.maximum !== undefined && value > schema.maximum) errors.push(`${path}: sopra il massimo`);
  }
  return errors;
}

const dataSchema = (type: string): Schema => {
  const doc = JSON.parse(readFileSync(join(SCHEMAS, `${type}.schema.json`), "utf8")) as Schema;
  return doc.properties?.data ?? (doc as Schema);
};

const row = (type: string, amount?: unknown) =>
  buildActionRow({ type, memberId: "MBR-000103", username: "giulia.ferri", amount, now: NOW, uid: UID });

describe("azioni inviabili", () => {
  const allowed = SEED.vetrinaTest.source.allowedTypes as string[];

  it("sono tutte ammesse dalla fonte di test (elenco esplicito) e hanno uno schema in contracts/events/action/", () => {
    expect(ACTION_TYPES.length).toBeGreaterThan(0);
    for (const a of ACTION_TYPES) {
      expect(allowed, a.type).toContain(a.type);
      expect(() => readFileSync(join(SCHEMAS, `${a.type}.schema.json`))).not.toThrow();
    }
  });

  it("l'importo è previsto solo per acquisto e reso", () => {
    expect(ACTION_TYPES.filter((a) => a.valued).map((a) => a.type)).toEqual(["purchase.completed", "purchase.returned"]);
  });
});

describe("riga dell'azione per tipo", () => {
  it.each(ACTION_TYPES.map((a) => [a.type, a.valued] as const))("%s: CloudEvent con fonte in forma di URN, soggetto membro, data valida per lo schema", (type, valued) => {
    const { row: r, file } = row(type, valued ? "150" : undefined);
    expect(r).toMatchObject({ specversion: "1.0", id: `vt-act-${UID}`, source: STORY_SOURCE_URN, type, subject: "member:MBR-000103", time: "2026-10-02T12:00:00Z" });
    expect(validate(dataSchema(type), r.data)).toEqual([]);
    expect(file.name).toBe(ACTION_FILE_NAME);
    expect(file.name).not.toBe(STORY_FILE_NAME);
    expect(JSON.parse(file.text.trim())).toEqual(r);
    expect(file.text.endsWith("\n")).toBe(true);
    // Mai e-mail né nome nel contenuto dell'import.
    expect(file.text).not.toMatch(/@|Giulia|Ferri/);
  });

  it("acquisto: importo con virgola o due decimali, valuta EUR, canale APP; reso: stesso importo", () => {
    expect(row("purchase.completed", "150,50").row.data).toMatchObject({ amount: 150.5, currency: "EUR", channel: "APP" });
    expect(row("purchase.completed", 19.99).row.data.amount).toBe(19.99);
    expect(row("purchase.returned", "40").row.data.amount).toBe(40);
  });

  it.each([["0"], ["-1"], ["abc"], [""], ["10000.01"], ["1.234"], ["12.345,6"], ["1e3"], ["0x10"], [" "], [true], [[5]], [{ v: 5 }], [null], [Number.NaN], [Infinity], [-3], [0], [10.005], [undefined]])(
    "importo non valido (%s): rifiutato",
    (amount) => {
      expect(() => row("purchase.completed", amount)).toThrow(/importo non valido/);
    },
  );

  it("«1.000» è ambiguo (migliaia o decimali?): rifiutato con un messaggio chiaro, anche come «12.345»", () => {
    expect(() => row("purchase.completed", "1.000")).toThrow(/«1\.000» è ambiguo.*senza separatore delle migliaia.*1000/);
    expect(() => row("purchase.completed", "12.345")).toThrow(/ambiguo/);
  });

  it.each([["150", 150], ["150,5", 150.5], ["150.50", 150.5], ["0,01", 0.01], ["10000", 10000], [" 20 ", 20], [99.99, 99.99]])("importo valido %s → %s", (amount, expected) => {
    expect(parseActionAmount(amount)).toBe(expected);
  });

  it("importo su un'azione senza valore o tipo sconosciuto: rifiutato", () => {
    expect(() => row("app.login.daily", "5")).toThrow(/non ha un importo/);
    expect(() => row("member.registered")).toThrow(/non inviabile/);
  });

  it("identificativo di invio non esadecimale: rifiutato", () => {
    expect(() => buildActionRow({ type: "app.login.daily", memberId: "MBR-1", now: NOW, uid: "ZZZ" })).toThrow(/non valido/);
  });
});

describe("lista bianca dell'import di una riga (assertActionImport)", () => {
  const ok = () => {
    const { file } = row("purchase.completed", "10");
    return { file, fields: { kind: "EVENTS", source: "vetrina-test" } };
  };
  const subjects = new Set(["MBR-000103"]);

  it("ammette la riga costruita, per un membro di test risolto", () => {
    expect(() => assertActionImport(ok(), subjects)).not.toThrow();
  });

  it("rifiuta un membro fuori dall'insieme dei risolti, anche se la riga è valida", () => {
    expect(() => assertActionImport(ok(), new Set(["MBR-000999"]))).toThrow(/soggetto fuori/);
    expect(() => assertActionImport(ok(), new Set())).toThrow(/nessun membro/);
  });

  it("rifiuta il nome dei file delle storie, altri nomi, altra fonte o altro kind", () => {
    const m = ok();
    expect(() => assertActionImport({ ...m, file: { ...m.file, name: STORY_FILE_NAME } }, subjects)).toThrow(/vetrina-azione/);
    expect(() => assertActionImport({ ...m, file: { ...m.file, name: "altro.ndjson" } }, subjects)).toThrow(/vetrina-azione/);
    expect(() => assertActionImport({ ...m, fields: { kind: "EVENTS", source: "ecommerce" } }, subjects)).toThrow(/fonte/);
    expect(() => assertActionImport({ ...m, fields: { kind: "MEMBERS", source: "vetrina-test" } }, subjects)).toThrow(/EVENTS/);
  });

  it("rifiuta la fonte in forma breve sulla riga (Q-258), un id non `vt-act-`, due righe, un tipo non inviabile", () => {
    const m = ok();
    const parsed = JSON.parse(m.file.text);
    const withFile = (o: object, extra = "") => ({ ...m, file: { ...m.file, text: `${JSON.stringify(o)}\n${extra}` } });
    expect(() => assertActionImport(withFile({ ...parsed, source: "vetrina-test" }), subjects)).toThrow(/URN/);
    expect(() => assertActionImport(withFile({ ...parsed, id: "vt-anna.rossi-01" }), subjects)).toThrow(/vt-act/);
    expect(() => assertActionImport(withFile(parsed, `${JSON.stringify(parsed)}\n`), subjects)).toThrow(/una sola riga/);
    expect(() => assertActionImport(withFile({ ...parsed, type: "member.registered" }), subjects)).toThrow(/non inviabile/);
    expect(() => assertActionImport(withFile({ ...parsed, subject: "member:MBR-000103@x" }), subjects)).toThrow(/soggetto/);
  });
});

describe("ambito delle richieste di «Invia un'azione»", () => {
  it("ammette la lettura del portafoglio, dei membri e degli import, e nessuna scrittura di configurazione", () => {
    expect(() => assertAllowedRequest("GET", "/v1/wallets/MBR-000103", undefined, ACTION_SCOPE)).not.toThrow();
    expect(() => assertAllowedRequest("GET", "/v1/members", undefined, ACTION_SCOPE)).not.toThrow();
    expect(() => assertAllowedRequest("GET", "/v1/imports/01J0000000000000000000001", undefined, ACTION_SCOPE)).not.toThrow();
    expect(() => assertAllowedRequest("POST", "/v1/rewards", {}, ACTION_SCOPE)).toThrow(/non ammessa/);
    expect(() => assertAllowedRequest("POST", "/v1/campaigns", {}, ACTION_SCOPE)).toThrow(/non ammessa/);
    expect(() => assertAllowedRequest("POST", "/v1/wallets/MBR-000103/adjust", {}, ACTION_SCOPE)).toThrow(/non ammessa/);
    // Il libro mastro serve ad attribuire i punti alla riga (per `actionId`); lotti e altro no.
    expect(() => assertAllowedRequest("GET", "/v1/wallets/MBR-000103/ledger", undefined, ACTION_SCOPE)).not.toThrow();
    expect(() => assertAllowedRequest("GET", "/v1/wallets/MBR-000103/lots", undefined, ACTION_SCOPE)).toThrow(/non ammessa/);
  });

  it("la lettura del portafoglio non è ammessa fuori da questo ambito (riga di comando, programma)", () => {
    expect(() => assertAllowedRequest("GET", "/v1/wallets/MBR-000103")).toThrow(/non ammessa/);
  });

  it("Api: l'import dell'azione passa dalla propria lista bianca e non dalle storie", async () => {
    const hub = makeHub({ members: { "giulia.ferri": "MBR-000103" } });
    const api = new Api({ transport: hub.transport, scope: ACTION_SCOPE });
    const story = SEED.vetrinaTest.stories.find((s) => s.username === "giulia.ferri")!;
    const id = await findTestMember(api, story);
    expect(id).toBe("MBR-000103");
    const { file } = row("app.login.daily");
    // Senza soggetti risolti: rifiutato prima di partire.
    await expect(api.request("ingestion", "POST", "/v1/imports", { multipart: { file, fields: { kind: "EVENTS", source: "vetrina-test" } } })).rejects.toThrow(/nessun membro/);
    api.storySubjects = new Set([id!]);
    await api.request("ingestion", "POST", "/v1/imports", { multipart: { file, fields: { kind: "EVENTS", source: "vetrina-test" } } });
    expect(hub.writes()).toHaveLength(1);
    // Un file con altro nome ricade nella lista bianca delle storie, che pretende righe `vt-<utente>-<nn>`.
    await expect(api.request("ingestion", "POST", "/v1/imports", { multipart: { file: { ...file, name: "x.ndjson" }, fields: { kind: "EVENTS", source: "vetrina-test" } } })).rejects.toThrow(/non ammesso/);
  });

  it("la lista bianca dell'import la decide l'AMBITO, non il nome del file (O4)", async () => {
    const hub = makeHub({ members: {} });
    const action = row("app.login.daily").file;
    const fields = { kind: "EVENTS", source: "vetrina-test" };
    const storyLine = JSON.stringify({ source: STORY_SOURCE_URN, subject: "member:MBR-000103", id: "vt-giulia.ferri-01", type: "app.login.daily", data: {} });
    const storyFile = { name: STORY_FILE_NAME, type: "application/x-ndjson", text: `${storyLine}\n` };
    // Azione singola: solo con ACTION_SCOPE. Con PROGRAM_SCOPE rifiutata anche con il nome «giusto».
    const program = new Api({ transport: hub.transport, scope: PROGRAM_SCOPE });
    program.storySubjects = new Set(["MBR-000103"]);
    await expect(program.request("ingestion", "POST", "/v1/imports", { multipart: { file: action, fields } })).rejects.toThrow(/non ammesso/);
    // Storie: solo con PROGRAM_SCOPE. Con ACTION_SCOPE rifiutate, anche con una riga dell'azione in un file «delle storie».
    const single = new Api({ transport: hub.transport, scope: ACTION_SCOPE });
    single.storySubjects = new Set(["MBR-000103"]);
    await expect(single.request("ingestion", "POST", "/v1/imports", { multipart: { file: storyFile, fields } })).rejects.toThrow(/non ammesso/);
    await expect(single.request("ingestion", "POST", "/v1/imports", { multipart: { file: { ...action, name: STORY_FILE_NAME }, fields } })).rejects.toThrow(/vetrina-azione/);
    // Né la riga di comando né un ambito senza storie possono importare.
    const none = new Api({ transport: hub.transport });
    await expect(none.request("ingestion", "POST", "/v1/imports", { multipart: { file: action, fields } })).rejects.toThrow(/non ammessa/);
    expect(hub.writes()).toEqual([]);
  });

  it("reso: l'etichetta dice che non storna punti (nessuna campagna del seed lo premia)", () => {
    expect(ACTION_TYPES.find((a) => a.type === "purchase.returned")?.label).toMatch(/non storna punti/);
    const campaigns = (SEED.campaigns ?? []) as { triggerActionTypes?: string[] }[];
    expect(campaigns.some((c) => (c.triggerActionTypes ?? []).includes("purchase.returned"))).toBe(false);
  });

  it("findTestMember: nessuna corrispondenza o più corrispondenze → null", async () => {
    const story = SEED.vetrinaTest.stories[0];
    const none = new Api({ transport: makeHub({ members: {} }).transport, scope: ACTION_SCOPE });
    expect(await findTestMember(none, story)).toBeNull();
  });
});
