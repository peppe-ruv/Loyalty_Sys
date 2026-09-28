import { describe, expect, it } from "vitest";
import { foldWords, looksPersonal, personalKeys, STANDARD_FIELDS, standardRow, technicalName } from "./fields";
import { rowErrors } from "./schema";
import eventTypes from "../../../seed/event-types.json";

// Guida ai campi (BO-09 sezione 3; Q-434) e blocco dei dati personali (regola 10, ADR-032; Q-435).

describe("campi standard", () => {
  it("riusano i nomi tecnici dei tipi di sistema", () => {
    const systemNames = new Set(
      (eventTypes as { dataSchema?: { properties?: Record<string, unknown> } }[]).flatMap((t) => Object.keys(t.dataSchema?.properties ?? {})),
    );
    for (const f of STANDARD_FIELDS.filter((x) => x.name !== "storeId")) expect(systemNames).toContain(f.name);
    expect(STANDARD_FIELDS.map((f) => f.name)).toEqual(["amount", "currency", "channel", "orderId", "productId", "storeId"]);
  });

  it("una riga standard è valida e porta etichetta e spiegazione", () => {
    const row = standardRow("channel", true);
    expect(row).toMatchObject({ name: "channel", label: "Canale", kind: "enum", options: "ONLINE, STORE, APP", required: true });
    expect(rowErrors(STANDARD_FIELDS.map((f) => standardRow(f.name)))).toEqual({});
    expect(() => standardRow("sconosciuto")).toThrow();
  });
});

describe("nome tecnico dall'etichetta italiana", () => {
  it("usa il nome standard quando l'etichetta corrisponde", () => {
    expect(technicalName("Importo")).toBe("amount");
    expect(technicalName("Codice del negozio")).toBe("storeId");
    expect(technicalName("Numero d'ordine")).toBe("orderId");
    expect(technicalName("Canale")).toBe("channel");
  });

  it("altrimenti camelCase senza accenti, articoli e cifre iniziali", () => {
    expect(technicalName("Data della visita")).toBe("dataVisita");
    expect(technicalName("Qualità del servizio")).toBe("qualitaServizio");
    expect(technicalName("3 volte al giorno")).toBe("volteGiorno");
    expect(technicalName("")).toBe("");
    expect(technicalName("!!!")).toBe("");
  });

  it("resta entro 40 caratteri, tagliando tra le parole", () => {
    const name = technicalName("identificativo univoco della prenotazione effettuata dal membro online");
    expect(name.length).toBeLessThanOrEqual(40);
    expect(name).toBe("identificativoUnivocoPrenotazione");
  });

  it("foldWords spezza anche il camelCase", () => {
    expect(foldWords("codiceFiscale")).toEqual(["codice", "fiscale"]);
  });
});

describe("dati personali", () => {
  it.each([
    "email",
    "eMail",
    "Indirizzo di casa",
    "telefono",
    "numeroTelefono",
    "Codice fiscale",
    "codiceFiscale",
    "taxCode",
    "firstName",
    "lastName",
    "Cognome",
    "Nome",
    "Nome del membro",
    "customerName",
    "Data di nascita",
    "birthDate",
    "iban",
    "CAP di residenza",
    "Cellulare (mobile)",
    "cf",
    "Partita IVA",
    "Nominativo",
    "Latitudine",
    "Numero civico",
  ])("blocca «%s»", (text) => {
    expect(looksPersonal(text)).toBe(true);
  });

  it("ogni ramo del controllo conta da solo", () => {
    // Solo la coppia «codice … fiscale» lo riconosce: nessuna parola da sola e nemmeno l'unione.
    expect(looksPersonal("Codice fiscale del membro")).toBe(true);
    // Solo l'unione delle parole («birthday») lo riconosce: nessuna coppia e nessuna parola da sola.
    expect(looksPersonal("Birth day")).toBe(true);
  });

  it("chiavi personali in un esempio, anche annidate", () => {
    expect(personalKeys({ storeId: "NEG-001", email: "x@y.it" })).toEqual(["email"]);
    expect(personalKeys({ items: [{ sku: "A", telefono: "1" }], meta: { codiceFiscale: "X" } })).toEqual(["telefono", "codiceFiscale"]);
    expect(personalKeys({ storeId: "NEG-001", amount: 1 })).toEqual([]);
    expect(personalKeys(null)).toEqual([]);
  });

  it.each(["Nome prodotto", "prizeName", "rewardName", "storeId", "memberId", "amount", "Canale", "Data della visita", "newsletter"])(
    "lascia passare «%s»",
    (text) => {
      expect(looksPersonal(text)).toBe(false);
    },
  );
});
