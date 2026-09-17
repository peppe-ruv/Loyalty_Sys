/**
 * Pattern 3, 4, 9 — ChoiceCards (LG-07, LG-11), SectionForm (LG-06) e InheritedSetting (LG-17).
 *
 * L'ordine delle sezioni è parte del contratto: ogni form del backoffice si legge nello stesso
 * ordine (tipo → base → logica → attributi → limiti → visibilità → stato), qualunque sia
 * l'ordine con cui il modulo le passa.
 */

import type { ReactNode } from 'react';
import type { EntityRef } from './common.js';
import type { WorkflowInfo } from './workflow.js';

// ------------------------------------------------------------------ ChoiceCards

export interface ChoiceCardOption<V extends string = string> {
  value: V;
  title: string;
  description: string;
  /** Esempi concreti mostrati nella card (achievement, LG-31/LG-32). */
  examples?: string[];
  disabled?: { reason: string };
}

export interface ChoiceCardsProps<V extends string = string> {
  legend: string;
  options: Array<ChoiceCardOption<V>>;
  value?: V;
  onChange: (value: V) => void;
  /** Cambiare la scelta ripulisce i campi dipendenti: il componente chiede conferma se true. */
  dirtyDependents?: boolean;
}

// ------------------------------------------------------------------ SectionForm

export const SECTION_ORDER = [
  'type',
  'basics',
  'logic',
  'customAttributes',
  'limits',
  'visibility',
  'state',
] as const;

export type SectionKind = (typeof SECTION_ORDER)[number];

/** Titoli di default delle sezioni: i moduli li sovrascrivono solo se hanno un nome migliore. */
export const SECTION_LABELS: Record<SectionKind, string> = {
  type: 'Tipo',
  basics: 'Informazioni di base',
  logic: 'Logica',
  customAttributes: 'Attributi personalizzati',
  limits: 'Limiti',
  visibility: 'Visibilità',
  state: 'Stato',
};

export interface FormSection {
  kind: SectionKind;
  title: string;
  /** Aiuto sotto il titolo che spiega la conseguenza, non la definizione (LG-08). */
  help?: string;
  learnMoreHref?: string;
  content: ReactNode;
}

export interface DependenciesPanelProps {
  usedBy: EntityRef[];
  uses: EntityRef[];
  /** L'eliminazione è bloccata se `usedBy` non è vuoto (RF-141). */
  onNavigate: (ref: EntityRef) => void;
}

export interface SectionFormProps {
  breadcrumb: Array<{ label: string; href?: string }>;
  /** Le sezioni sono renderizzate nell'ordine fisso di {@link SECTION_ORDER}, non in quello dell'array. */
  sections: FormSection[];
  workflow: WorkflowInfo;
  /** Riquadro «Usato da / Usa» (RF-141): presente su segmenti, campagne, achievement, collection, premi. */
  dependencies?: DependenciesPanelProps;
  primaryAction: { label: string; onClick: () => void; disabled?: boolean };
  secondaryAction?: { label: string; onClick: () => void };
  /** Avviso rosso fisso sopra la logica quando il salvataggio distrugge dati (LG-09, LG-33). */
  destructiveWarning?: { message: string; affectedCount?: number };
}

/** Ordina le sezioni secondo {@link SECTION_ORDER} mantenendo stabile l'ordine a parità di `kind`. */
export function sortSections<T extends { kind: SectionKind }>(sections: readonly T[]): T[] {
  return [...sections].sort((a, b) => SECTION_ORDER.indexOf(a.kind) - SECTION_ORDER.indexOf(b.kind));
}

/** True se l'eliminazione va bloccata perché l'oggetto è ancora referenziato (RF-141). */
export function isDeletionBlocked(dependencies: Pick<DependenciesPanelProps, 'usedBy'> | undefined): boolean {
  return (dependencies?.usedBy.length ?? 0) > 0;
}

// ------------------------------------------------------------------ InheritedSetting

export interface InheritedSettingValue<T> {
  override: boolean;
  inherited: T;
  /** Frase del valore ereditato: «Scadenza: dopo 365 giorni (dal wallet Premio)». */
  inheritedDisplay: string;
  value?: T;
}

export interface InheritedSettingProps<T> {
  label: string;
  value: InheritedSettingValue<T>;
  /** Avviso mostrato quando override è acceso: «vale solo per questo effetto». */
  scopeWarning: string;
  renderFields: (value: T, onChange: (next: T) => void) => ReactNode;
  onChange: (next: InheritedSettingValue<T>) => void;
}

/** Valore effettivo di un'impostazione ereditabile: l'override vince solo se valorizzato (LG-17). */
export function effectiveValue<T>(setting: InheritedSettingValue<T>): T {
  return setting.override && setting.value !== undefined ? setting.value : setting.inherited;
}
