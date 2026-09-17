/**
 * Stato di workflow degli oggetti configurabili (D14, RF-137).
 *
 * Sostituisce il toggle Attivo/Inattivo di Open Loyalty: il ciclo è
 * Bozza → In revisione → Approvato → (Verifica Legal) → Programmato → Pubblicato → Bloccato,
 * con la verifica Legal richiesta solo per alcuni tipi di oggetto (regole, tier, concorsi,
 * programma, decisioni). La tabella delle transizioni è qui e non nei singoli moduli: è ciò
 * che rende uniforme l'ultima sezione di ogni form.
 */

import type { IsoDateTime } from './common.js';

export const WORKFLOW_STATES = [
  'draft',
  'inReview',
  'approved',
  'legalReview',
  'scheduled',
  'published',
  'locked',
] as const;

export type WorkflowState = (typeof WORKFLOW_STATES)[number];

/** Etichette mostrate all'utente; l'API non espone mai il valore tecnico (RF-139). */
export const WORKFLOW_STATE_LABELS: Record<WorkflowState, string> = {
  draft: 'Bozza',
  inReview: 'In revisione',
  approved: 'Approvato',
  legalReview: 'Verifica Legal',
  scheduled: 'Programmato',
  published: 'Pubblicato',
  locked: 'Bloccato',
};

/** Testo che spiega la conseguenza dello stato, non la definizione (LG-06, LG-08). */
export const WORKFLOW_STATE_HELP: Record<WorkflowState, string> = {
  draft: 'Visibile solo a chi lavora sull’oggetto: non produce alcun effetto sui membri.',
  inReview: 'In attesa del responsabile: il contenuto è congelato finché la revisione non si chiude.',
  approved: 'Approvato dal responsabile: manca solo la pubblicazione per renderlo operativo.',
  legalReview: 'In verifica presso Legal: senza il via libera l’oggetto non può essere pubblicato.',
  scheduled: 'Partirà da solo alla data programmata; fino ad allora non produce effetti.',
  published: 'Attivo: sta già producendo effetti sui membri.',
  locked: 'Bloccato perché il concorso è avviato: la logica non è più modificabile (DPR 430).',
};

/** Ruoli del backoffice che governano le transizioni (collezione `roles`, RF-43). */
export const WORKFLOW_ROLES = ['editor', 'reviewer', 'legal', 'publisher', 'admin'] as const;

export type WorkflowRole = (typeof WORKFLOW_ROLES)[number];

/** Passaggio disponibile per l'utente corrente, già filtrato per ruolo (RF-43). */
export interface WorkflowTransition {
  to: WorkflowState;
  label: string;
  /** true se il passaggio chiede un commento obbligatorio prima di essere eseguito (RF-140). */
  requiresComment?: boolean;
}

export interface WorkflowInfo {
  state: WorkflowState;
  /** Chi ha portato l'oggetto nello stato corrente e quando (RF-41). */
  by?: { user: string; at: IsoDateTime };
  transitions: WorkflowTransition[];
  /** Testo che spiega l'effetto dello stato (LG-06). */
  helpText: string;
}

/** Condizioni dell'oggetto e dell'utente che filtrano le transizioni. */
export interface WorkflowContext {
  /** Ruoli dell'utente corrente. */
  roles: readonly WorkflowRole[];
  /** true per i tipi di oggetto che richiedono il via libera di Legal (regole, tier, concorsi, programma, decisioni). */
  legalRequired?: boolean;
  /** true quando Legal ha già dato il via libera alla revisione corrente. */
  legalApproved?: boolean;
}

interface TransitionRule extends WorkflowTransition {
  from: WorkflowState;
  roles: readonly WorkflowRole[];
  /** Vincolo aggiuntivo oltre al ruolo; assente significa «sempre disponibile». */
  when?: (context: WorkflowContext) => boolean;
}

const needsLegal = (context: WorkflowContext): boolean =>
  context.legalRequired === true && context.legalApproved !== true;

const legalCleared = (context: WorkflowContext): boolean => !needsLegal(context);

/**
 * Tabella unica delle transizioni. L'ordine è quello mostrato nel menu di stato del form:
 * prima l'avanzamento, poi i ritorni indietro.
 */
export const WORKFLOW_TRANSITIONS: readonly TransitionRule[] = [
  { from: 'draft', to: 'inReview', label: 'Invia in revisione', roles: ['editor', 'reviewer', 'publisher', 'admin'] },
  { from: 'inReview', to: 'approved', label: 'Approva', roles: ['reviewer', 'publisher', 'admin'] },
  { from: 'inReview', to: 'draft', label: 'Rimanda in bozza', requiresComment: true, roles: ['reviewer', 'publisher', 'admin'] },
  { from: 'approved', to: 'legalReview', label: 'Invia alla verifica Legal', roles: ['reviewer', 'publisher', 'admin'], when: needsLegal },
  { from: 'approved', to: 'scheduled', label: 'Programma la pubblicazione', roles: ['publisher', 'admin'], when: legalCleared },
  { from: 'approved', to: 'published', label: 'Pubblica', roles: ['publisher', 'admin'], when: legalCleared },
  { from: 'approved', to: 'draft', label: 'Riporta in bozza', requiresComment: true, roles: ['reviewer', 'publisher', 'admin'] },
  { from: 'legalReview', to: 'approved', label: 'Via libera di Legal', roles: ['legal', 'admin'] },
  { from: 'legalReview', to: 'draft', label: 'Respingi (Legal)', requiresComment: true, roles: ['legal', 'admin'] },
  { from: 'scheduled', to: 'published', label: 'Pubblica subito', roles: ['publisher', 'admin'] },
  { from: 'scheduled', to: 'approved', label: 'Annulla la programmazione', roles: ['publisher', 'admin'] },
  { from: 'published', to: 'draft', label: 'Ritira e riporta in bozza', requiresComment: true, roles: ['publisher', 'admin'] },
  { from: 'published', to: 'locked', label: 'Blocca (concorso avviato)', roles: ['admin'] },
];

/** True se `value` è uno stato di workflow noto (usata al confine con l'API). */
export function isWorkflowState(value: unknown): value is WorkflowState {
  return typeof value === 'string' && (WORKFLOW_STATES as readonly string[]).includes(value);
}

/** Transizioni disponibili da `state` per l'utente descritto da `context`, nell'ordine di menu. */
export function availableTransitions(state: WorkflowState, context: WorkflowContext): WorkflowTransition[] {
  return WORKFLOW_TRANSITIONS.filter(
    (rule) =>
      rule.from === state &&
      rule.roles.some((role) => context.roles.includes(role)) &&
      (rule.when?.(context) ?? true),
  ).map(({ from: _from, roles: _roles, when: _when, ...transition }) => transition);
}

/** True se il passaggio `from → to` è consentito all'utente descritto da `context`. */
export function canTransition(from: WorkflowState, to: WorkflowState, context: WorkflowContext): boolean {
  return availableTransitions(from, context).some((transition) => transition.to === to);
}

/** True se il passaggio richiede un commento obbligatorio (RF-140). */
export function transitionRequiresComment(from: WorkflowState, to: WorkflowState): boolean {
  return WORKFLOW_TRANSITIONS.some(
    (rule) => rule.from === from && rule.to === to && rule.requiresComment === true,
  );
}

/** Costruisce la sezione «Stato» del form: etichetta, aiuto e transizioni già filtrate (LG-06, RF-137). */
export function buildWorkflowInfo(
  state: WorkflowState,
  context: WorkflowContext,
  by?: WorkflowInfo['by'],
): WorkflowInfo {
  return {
    state,
    transitions: availableTransitions(state, context),
    helpText: WORKFLOW_STATE_HELP[state],
    ...(by === undefined ? {} : { by }),
  };
}
