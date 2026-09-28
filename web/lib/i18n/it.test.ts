import { describe, expect, it as test } from "vitest";
import { it } from "./it";
import { STANDARD_FIELDS } from "@/lib/actiontypes/fields";
import { ACTION_TEMPLATES } from "@/lib/actiontypes/templates";
import { VERB_CHOICES } from "@/lib/actiontypes/code";
import { KIND_LABEL, ROW_ERROR } from "@/lib/actiontypes/schema";
import { CATEGORY_LABEL, ORIGIN_LABEL, SOURCE_KIND_LABEL } from "@/lib/actiontypes/types";
import { ACTION_ICONS } from "@/lib/icons/action-icons";

// Testi di BO-09 e della scorciatoia da BO-06 (Q-438): tutti in `lib/i18n/it.ts`, non vuoti, e il partecipante al
// programma si chiama «membro» (AGENTS.md), mai «socio», «cliente» o «utente».

function strings(node: unknown, path: string, out: [string, string][]) {
  if (typeof node === "string") out.push([path, node]);
  else if (typeof node === "function") {
    const fn = node as (...a: unknown[]) => unknown;
    const args = Array.from({ length: fn.length }, (_, i) => (i === 0 ? "Esempio" : 2));
    strings(fn(...args), `${path}()`, out);
  } else if (Array.isArray(node)) node.forEach((v, i) => strings(v, `${path}[${i}]`, out));
  else if (node && typeof node === "object") for (const [k, v] of Object.entries(node)) strings(v, `${path}.${k}`, out);
  return out;
}

describe("dizionario di Azioni e fonti", () => {
  const all = strings(it.actions, "actions", []);

  test("ha i testi chiave della riprogettazione", () => {
    expect(all.length).toBeGreaterThan(150);
    expect(it.actions.tabs).toEqual({ types: "Azioni", sources: "Fonti", bridge: "Azioni generate dal programma" });
    expect(it.actions.how.demo).toBe("In demo, il ripristino dei dati elimina le azioni personalizzate.");
    expect(it.actions.picker.created("Visita in negozio")).toBe("Visita in negozio è stata creata e aggiunta a Quando.");
    expect(it.actions.picker.createFrom("Degustazione")).toBe("Crea «Degustazione» come nuova azione");
    expect(it.actions.editor.impact(1, 1)).toMatch(/^Usata da 1 campagna \(1 attiva\)/);
    expect(it.actions.editor.impact(3, 2)).toMatch(/^Usata da 3 campagne \(2 attive\)/);
    expect(it.actions.list.campaigns(1)).toBe("1 campagna");
    expect(it.actions.list.campaigns(0)).toBe("0 campagne");
  });

  test("nessun testo vuoto", () => {
    for (const [path, s] of all) expect(s.trim(), path).not.toBe("");
  });

  test("«membro», mai «socio», «cliente» o «utente»", () => {
    for (const [path, s] of all) expect(s, path).not.toMatch(/\b(soci[oa]?|client[ei]|utent[ei])\b/i);
  });

  test("anche i testi fuori dal dizionario (campi standard, modelli, icone, etichette) dicono «membro»", () => {
    // Finché M11 non sposta ogni testo nel dizionario, il controllo copre anche le tabelle dei moduli di BO-09.
    const extra = strings(
      {
        STANDARD_FIELDS,
        ACTION_TEMPLATES,
        VERB_CHOICES,
        ROW_ERROR,
        KIND_LABEL,
        icons: ACTION_ICONS.map((i) => [i.label, i.keywords ?? ""]),
        CATEGORY_LABEL,
        ORIGIN_LABEL,
        SOURCE_KIND_LABEL,
      },
      "moduli",
      [],
    );
    expect(extra.length).toBeGreaterThan(100);
    for (const [path, s] of extra) expect(s, path).not.toMatch(/\b(soci[oa]?|client[ei]|utent[ei])\b/i);
  });

  test("niente gergo tecnico nella spiegazione del ponte (resta sotto «Dettagli tecnici»)", () => {
    expect(it.actions.bridge.intro).not.toMatch(/lhhop|DLQ|LOOP_GUARD/);
    expect(it.actions.bridge.technicalText).toMatch(/lhhop/);
  });
});
