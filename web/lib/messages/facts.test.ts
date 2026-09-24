import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { FACTS, factLabel, fieldKind, placeholdersFor, sampleEvent } from "./facts";

const ROOT = resolve(__dirname, "../../..");

/** Tipi di fatto del catalogo backend (LhEventTypes.Fact), forma breve. */
function backendFactTypes(): string[] {
  const src = readFileSync(resolve(ROOT, "libs/lh-common/src/main/java/io/loyaltyhub/common/event/LhEventTypes.java"), "utf8");
  const block = src.slice(src.indexOf("class Fact"), src.indexOf("private Fact()"));
  return [...block.matchAll(/PREFIX \+ "([a-z.]+)"/g)].map((m) => m[1]);
}

describe("catalogo dei fatti di BO-19", () => {
  it("coincide con i tipi ammessi da engagement (tutti i fatti tranne message.delivered)", () => {
    const expected = backendFactTypes().filter((t) => t !== "message.delivered").sort();
    expect(FACTS.map((f) => f.type).sort()).toEqual(expected);
  });

  it("i campioni rispettano i contratti: campi obbligatori presenti, nessun campo inventato", () => {
    for (const f of FACTS) {
      const path = resolve(ROOT, `contracts/events/fact/${f.type}.schema.json`);
      if (!existsSync(path)) continue; // es. member.birthday, member.segment.*: nessuno schema dedicato
      const schema = JSON.parse(readFileSync(path, "utf8")) as { required?: string[]; properties?: Record<string, unknown> };
      const keys = Object.keys(f.sample);
      for (const r of schema.required ?? []) expect(keys, `${f.type} richiede ${r}`).toContain(r);
      for (const k of keys) expect(Object.keys(schema.properties ?? {}), `${f.type}.${k}`).toContain(k);
    }
  });

  it("ha un'etichetta in italiano e ricade sul codice per i tipi ignoti", () => {
    expect(factLabel("tier.upgraded")).toBe("Salita di livello");
    expect(factLabel("x.y")).toBe("x.y");
  });
});

describe("segnaposto suggeriti", () => {
  it("propongono i campi data.* delle sorgenti con il formattatore adatto, senza duplicati", () => {
    const tokens = placeholdersFor([
      { kind: "fact", factType: "wallet.points.earned" },
      { kind: "fact", factType: "wallet.points.expiring" },
    ]).map((p) => p.token);
    expect(tokens).toContain("{{data.amount|number}}");
    expect(tokens).toContain("{{data.expiresAt|date}}");
    expect(tokens).toContain("{{data.currency}}");
    expect(tokens.filter((t) => t === "{{data.amount|number}}")).toHaveLength(1);
    expect(tokens).toContain("{{member.firstName}}");
  });

  it("per una campagna con SEND_MESSAGE includono i campi dell'effetto e i params", () => {
    const tokens = placeholdersFor([{ kind: "campaign", campaignCode: "CMP-BIRTHDAY", params: { age: 39 } }], "MSG-BIRTHDAY").map((p) => p.token);
    expect(tokens).toContain("{{data.campaignCode}}");
    expect(tokens).toContain("{{data.age|number}}");
  });

  it("senza sorgenti restano solo quelli comuni", () => {
    expect(placeholdersFor([]).map((p) => p.group)).toEqual(["member", "member", "event"]);
  });

  it("riconosce numeri e date", () => {
    expect(fieldKind("amount", 162)).toBe("number");
    expect(fieldKind("expiresAt", "2026-10-31T22:59:59Z")).toBe("date");
    expect(fieldKind("currency", "PTS")).toBe("text");
  });
});

describe("evento campione per l'anteprima", () => {
  it("è un fatto sul membro scelto col data di esempio", () => {
    const e = sampleEvent({ kind: "fact", factType: "tier.upgraded" }, "MSG-TIER-UP", "MBR-000003", new Date("2026-09-24T10:00:00Z"));
    expect(e.type).toBe("io.loyaltyhub.fact.tier.upgraded");
    expect(e.subject).toBe("member:MBR-000003");
    expect(e.time).toBe("2026-09-24T10:00:00.000Z");
    expect(e.data).toMatchObject({ newTier: "GOLD" });
  });

  it("per una campagna è un effetto message.send con i params fusi in data", () => {
    const e = sampleEvent({ kind: "campaign", campaignCode: "CMP-BIRTHDAY", params: { age: 39 } }, "MSG-BIRTHDAY", "MBR-000002");
    expect(e.type).toBe("io.loyaltyhub.effect.message.send");
    expect(e.data).toMatchObject({ campaignCode: "CMP-BIRTHDAY", templateCode: "MSG-BIRTHDAY", age: 39 });
  });
});
