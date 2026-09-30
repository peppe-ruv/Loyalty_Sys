// seed-schema.mjs — validazione dei seed contro seed/_schemas/ (docs/10 §11 regola 1, docs/12 M0.7, ADR-015).
// Usato da check-seed.mjs. Gli schemi sono JSON Schema 2020-12 come tutti i contratti del repo (docs/05 §9, ADR-009).
// ajv è una dipendenza di scripts/ (`npm --prefix scripts ci`): si carica solo quando esiste almeno uno schema,
// così `node scripts/check-seed.mjs` resta eseguibile senza installazione finché _schemas/ è vuota.

/** Carica ajv (dialetto 2020-12) e ajv-formats; se mancano, errore chiaro in italiano. */
async function loadAjv() {
  try {
    const { default: Ajv2020 } = await import("ajv/dist/2020.js");
    const { default: addFormats } = await import("ajv-formats");
    return { Ajv2020, addFormats };
  } catch (e) {
    if (e?.code === "ERR_MODULE_NOT_FOUND" || e?.code === "MODULE_NOT_FOUND") {
      throw new Error(`dipendenze degli script mancanti (${e.message.split("\n")[0]}): esegui \`npm --prefix scripts ci\``);
    }
    throw e;
  }
}

/** Una riga leggibile per errore ajv: percorso (radice = "/"), messaggio e, se utili, i parametri. */
export function formatAjvError(err) {
  const params = err.params && Object.keys(err.params).length > 0 ? ` ${JSON.stringify(err.params)}` : "";
  return `${err.instancePath || "/"} ${err.message}${params}`;
}

/**
 * Crea un validatore per un insieme di schemi { nomeFile: oggettoSchema }.
 * - Dialetto 2020-12 (Ajv2020): `$schema` 2020-12 si risolve, `prefixItems` è supportato.
 * - `strict: false`: le annotazioni `x-lh-*` (es. `x-lh-pii`, `x-lh-superseded-by`, docs/05 §9) non sono errori.
 * - `format` è asserito (ajv-formats) sui seed: scelta volutamente più rigida dei servizi, che con networknt lo
 *   trattano come annotazione (Q-43); un seed con una data o un URI malformati non è un seed valido.
 * - Tutti gli schemi sono registrati prima di compilare, così i `$ref` tra file di _schemas/ si risolvono.
 * Ritorna { validate(nomeFile, dati) -> string[] }; lancia se lo schema non è compilabile.
 */
export async function createSeedValidator(schemas) {
  const { Ajv2020, addFormats } = await loadAjv();
  const ajv = new Ajv2020({ allErrors: true, strict: false });
  addFormats(ajv);
  const registrationErrors = new Map();
  for (const [name, schema] of Object.entries(schemas)) {
    try {
      ajv.addSchema(schema, name);
    } catch (e) {
      registrationErrors.set(name, e);
    }
  }
  return {
    validate(name, data) {
      if (registrationErrors.has(name)) throw registrationErrors.get(name);
      const validateFn = ajv.getSchema(name);
      if (!validateFn) throw new Error(`nessuno schema registrato per ${name}`);
      return validateFn(data) ? [] : validateFn.errors.map(formatAjvError);
    },
  };
}
