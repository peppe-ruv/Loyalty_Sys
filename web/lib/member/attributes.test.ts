import { describe, expect, it } from "vitest";
import { addLabel, attributePatch, fromFormValue, normalizeLabel, toFormValue, type AttributeDefinition } from "./attributes";

const DEFS: AttributeDefinition[] = [
  { key: "householdSize", label: "Componenti", type: "NUMBER", options: [] },
  { key: "hasGasContract", label: "Gas", type: "BOOLEAN", options: [] },
  { key: "preferredChannel", label: "Canale", type: "STRING", options: ["APP", "WEB", "STORE"] },
  { key: "since", label: "Dal", type: "DATE", options: [] },
];

describe("attributi del membro", () => {
  it("converte i valori tra form e servizio", () => {
    expect(toFormValue(DEFS[1], false)).toBe("false");
    expect(fromFormValue(DEFS[0], "3,5")).toBe(3.5);
    expect(fromFormValue(DEFS[0], "tre")).toBeUndefined();
    expect(fromFormValue(DEFS[2], "FAX")).toBeUndefined();
    expect(fromFormValue(DEFS[3], "2026-02-30x")).toBeUndefined();
    expect(fromFormValue(DEFS[3], "")).toBeNull();
  });

  it("manda solo le differenze e null per i campi svuotati", () => {
    const current = { householdSize: 3, hasGasContract: true, preferredChannel: "WEB" };
    const { attributes, errors } = attributePatch(DEFS, current, { householdSize: "4", hasGasContract: "", preferredChannel: "WEB" });
    expect(errors).toEqual({});
    expect(attributes).toEqual({ householdSize: 4, hasGasContract: null });
  });

  it("riporta gli errori per chiave", () => {
    expect(attributePatch(DEFS, {}, { householdSize: "molti", preferredChannel: "FAX" }).errors).toEqual({
      householdSize: "Serve un numero",
      preferredChannel: "Uno tra APP, WEB, STORE",
    });
  });

  it("normalizza le etichette e scarta doppioni e non valide", () => {
    expect(normalizeLabel(" VIP ")).toBe("vip");
    expect(normalizeLabel("non valida!")).toBeNull();
    expect(addLabel(["vip"], "VIP")).toEqual(["vip"]);
    expect(addLabel(["vip"], "newsletter")).toEqual(["vip", "newsletter"]);
  });
});
