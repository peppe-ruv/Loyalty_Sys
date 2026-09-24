import { describe, expect, it } from "vitest";
import { emptyTemplateForm, errorsByField, insertAt, templateProblems, templateUsage, validateTemplateForm } from "./templates";
import type { NotificationRule } from "./types";

const rule = (code: string, factType: string, templateCode: string): NotificationRule => ({
  id: code,
  code,
  factType,
  condition: null,
  templateCode,
  enabled: true,
  version: 0,
  updatedAt: null,
  updatedBy: null,
});

describe("templateProblems (come TemplateEngine.problems)", () => {
  it("accetta segnaposto validi con o senza formattatore", () => {
    expect(templateProblems("Hai guadagnato {{data.amount|number}} punti, {{ member.firstName }}")).toEqual([]);
    expect(templateProblems("Scadono il {{data.expiresAt | date}}")).toEqual([]);
    expect(templateProblems("")).toEqual([]);
  });

  it("segnala radice sconosciuta, formattatore sconosciuto, segnaposto vuoto e graffe spaiate", () => {
    expect(templateProblems("{{amount}}")[0]).toContain("data., member. o event.");
    expect(templateProblems("{{data.amount|euro}}")).toContain("formattatore sconosciuto |euro (ammessi: number, date)");
    expect(templateProblems("{{ }}")).toContain("segnaposto vuoto");
    expect(templateProblems("Ciao {{member.firstName")).toContain("graffe {{ }} non bilanciate");
  });
});

describe("insertAt", () => {
  it("inserisce nel punto del cursore o sostituisce la selezione", () => {
    expect(insertAt("Ciao !", "{{member.firstName}}", 5, 5)).toEqual({ text: "Ciao {{member.firstName}}!", caret: 25 });
    expect(insertAt("Ciao XX!", "{{member.firstName}}", 5, 7)).toEqual({ text: "Ciao {{member.firstName}}!", caret: 25 });
    expect(insertAt("Ciao", "!", null, null)).toEqual({ text: "Ciao!", caret: 5 });
  });
});

describe("templateUsage", () => {
  it("trova regole e campagne che usano il template e ne ricava le sorgenti dell'anteprima", () => {
    const rules = [rule("NR-TIER-UP", "tier.upgraded", "MSG-TIER-UP"), rule("NR-OTHER", "wallet.points.earned", "MSG-POINTS-EARNED")];
    const campaigns = [
      { code: "CMP-BIRTHDAY", name: "Buon compleanno", status: "LIVE", effects: [{ type: "GRANT_POINTS" }, { type: "SEND_MESSAGE", templateCode: "MSG-BIRTHDAY" }] },
      { code: "CMP-WELCOME", name: "Benvenuto", status: "LIVE", effects: [{ type: "GRANT_POINTS" }] },
    ];
    const tierUp = templateUsage("MSG-TIER-UP", rules, campaigns);
    expect(tierUp.usedByRules.map((r) => r.code)).toEqual(["NR-TIER-UP"]);
    expect(tierUp.usedByCampaigns).toEqual([]);
    expect(tierUp.sources).toEqual([{ kind: "fact", factType: "tier.upgraded" }]);

    const birthday = templateUsage("MSG-BIRTHDAY", rules, campaigns);
    expect(birthday.usedByRules).toEqual([]);
    expect(birthday.usedByCampaigns.map((c) => c.code)).toEqual(["CMP-BIRTHDAY"]);
    expect(birthday.sources).toEqual([{ kind: "campaign", campaignCode: "CMP-BIRTHDAY", params: {} }]);
  });
});

describe("validazione del modulo", () => {
  it("richiede codice, nome, titolo e testo validi", () => {
    const errors = validateTemplateForm({ ...emptyTemplateForm(), code: "1-MSG", titleTpl: "{{amount}}" }, true);
    expect(Object.keys(errors).sort()).toEqual(["bodyTpl", "code", "name", "titleTpl"]);
    expect(errors.titleTpl[0]).toContain("data., member. o event.");
  });

  it("in modifica il codice non si valida; il link deve restare nel portale", () => {
    const f = { ...emptyTemplateForm(), code: "x", name: "N", titleTpl: "T", bodyTpl: "B", linkTarget: "https://example.org" };
    expect(Object.keys(validateTemplateForm(f, false))).toEqual(["linkTarget"]);
    expect(validateTemplateForm({ ...f, linkTarget: "" }, false)).toEqual({});
  });

  it("raggruppa gli errori del backend per campo", () => {
    expect(
      errorsByField([
        { field: "titleTpl", message: "obbligatorio" },
        { field: "titleTpl", message: "graffe" },
        { field: "category", message: "tra [...]" },
      ]),
    ).toEqual({ titleTpl: ["obbligatorio", "graffe"], category: ["tra [...]"] });
    expect(errorsByField(undefined)).toEqual({});
  });
});
