/**
 * Tipi comuni a tutti i pattern del design system.
 *
 * Regola generale (RF-139): nessun tipo espone identificativi tecnici destinati a frasi o chip.
 * Le entità referenziate viaggiano sempre come {@link EntityRef} e la UI mostra solo `label`;
 * l'`id` serve al salvataggio e compare unicamente nella scheda identità con icona di copia.
 */

/** Istante in formato ISO 8601 con fuso, es. `2026-09-17T08:30:00+02:00`. */
export type IsoDateTime = string;

/** Data in formato ISO 8601 senza orario, es. `2026-09-17`. */
export type IsoDate = string;

/** Tipi di oggetto referenziabili dal backoffice. */
export const ENTITY_KINDS = [
  'segment',
  'collection',
  'achievement',
  'eventSchema',
  'reward',
  'campaign',
  'wallet',
  'tier',
  'tierSet',
  'member',
] as const;

export type EntityKind = (typeof ENTITY_KINDS)[number];

/**
 * Riferimento a un oggetto configurabile: l'id serve al salvataggio, la label è l'unica cosa
 * mostrata (LG-01, LG-13, RF-139).
 */
export interface EntityRef {
  id: string;
  label: string;
  kind: EntityKind;
  /** true se l'oggetto è in stato diverso da Pubblicato: la chip lo mostra in grigio (LG-04). */
  inactive?: boolean;
}

/** Opzione semplice di una select, quando il valore non è un oggetto del dominio. */
export interface SelectOption {
  value: string;
  label: string;
}

/** Sorgente delle opzioni di un campo a scelta: entità del dominio oppure valori di un enum. */
export type OptionSource = readonly EntityRef[] | readonly SelectOption[];

/**
 * Stringa libera che non assorbe i letterali di un'unione: `'EUR' | OpenString` resta suggerita
 * dall'editor invece di collassare su `string`.
 */
export type OpenString = string & Record<never, never>;

/** Unità di misura mostrata come suffisso nel campo (LG-29) o nell'intestazione di colonna (LG-04). */
export type Unit = 'EUR' | 'punti' | 'giorni' | 'volte' | 'mesi' | '%' | OpenString;

/** Lingue del backoffice; `it` è la lingua di default e l'unica obbligatoria (RF-79). */
export type Locale = 'it' | 'en' | OpenString;

/**
 * Testo localizzato per campo (LG-10, RF-79): l'italiano è sempre presente, le altre lingue
 * sono facoltative e ricadono sull'italiano quando mancano.
 */
export type LocalizedText = { it: string } & Partial<Record<Locale, string>>;

/** True se `value` è un {@link EntityRef} completo (guardia usata prima di comporre frasi e chip). */
export function isEntityRef(value: unknown): value is EntityRef {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as Partial<EntityRef>;
  return (
    typeof candidate.id === 'string' &&
    typeof candidate.label === 'string' &&
    typeof candidate.kind === 'string' &&
    (ENTITY_KINDS as readonly string[]).includes(candidate.kind)
  );
}

/** Testo da mostrare per un'entità: la label, mai l'id (RF-139). */
export function entityLabel(ref: EntityRef): string {
  return ref.label;
}

/** Testo localizzato nella lingua richiesta, con ricaduta sull'italiano (RF-79). */
export function localized(text: LocalizedText, locale: Locale = 'it'): string {
  return text[locale] ?? text.it;
}
