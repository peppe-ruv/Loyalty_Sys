/**
 * Pattern 12 e 13 — EntityProfile (LG-35, LG-26) e Timeline (LG-37).
 *
 * Il profilo membro è l'unico punto in cui compaiono identificativi tecnici, nella scheda
 * identità con icona di copia (RF-139); ogni operazione manuale lascia autore e motivo (RF-140).
 */

import type { ReactNode } from 'react';
import type { EntityRef, IsoDateTime, Unit } from './common.js';
import type { FilterBuilderProps } from './filters.js';

export interface IdentityField {
  label: string;
  value: string;
  copyable?: boolean;
  /** «tra 12 giorni» per le date future, come il compleanno. */
  relative?: string;
}

export interface TierPanel {
  current: EntityRef;
  /** Fino a quando il livello è congelato (lucchetto, LG-26). */
  lockedUntil?: IsoDateTime;
  lastPromotion?: IsoDateTime;
  lastDemotion?: IsoDateTime;
  nextRecalc?: IsoDateTime;
  progressToNext: { value: number; threshold: number; unit: Unit };
}

export interface ProfileAction {
  label: string;
  onSelect: () => void;
  /** Commento obbligatorio su ogni operazione manuale (RF-140). */
  requiresComment?: boolean;
  /** Mostra «Richiede seconda approvazione» oltre la soglia quattro occhi (RF-18, LG-38). */
  fourEyesAboveThreshold?: boolean;
}

export interface EntityProfileProps {
  initials: string;
  status: { label: string; tone: 'ok' | 'warn' | 'bad' | 'muted' };
  identity: IdentityField[];
  chips: Array<{ label: string; kind: 'tier' | 'segment' | 'risk' }>;
  tabs: Array<{ key: string; label: string; content: ReactNode; badge?: number }>;
  actions: ProfileAction[];
  /** Riquadro tier con lucchetto e progresso (LG-26). */
  tierPanel?: TierPanel;
}

export const TIMELINE_EVENT_TYPES = [
  'MOVIMENTO',
  'PREMIO',
  'TRANSAZIONE',
  'CAMBIO_TIER',
  'GIOCATA',
  'ACHIEVEMENT',
  'DECISIONE',
  'CONSENSO',
  'NOTA',
] as const;

export type TimelineEventType = (typeof TIMELINE_EVENT_TYPES)[number];

/** Etichette leggibili dei tipi di evento: la timeline non mostra mai il valore tecnico (RF-139). */
export const TIMELINE_EVENT_LABELS: Record<TimelineEventType, string> = {
  MOVIMENTO: 'Movimento punti',
  PREMIO: 'Premio',
  TRANSAZIONE: 'Transazione',
  CAMBIO_TIER: 'Cambio livello',
  GIOCATA: 'Giocata',
  ACHIEVEMENT: 'Achievement',
  DECISIONE: 'Decisione',
  CONSENSO: 'Consenso',
  NOTA: 'Nota',
};

export interface TimelineEvent {
  id: string;
  at: IsoDateTime;
  type: TimelineEventType;
  /** Frase, non codice: «Guadagnati 984 punti premio: bolletta pagata puntuale». */
  title: string;
  meta: Array<{ label: string; value: string }>;
  /** Saldo attivo dopo il movimento, per wallet. */
  balanceAfter?: Array<{ wallet: EntityRef; value: number }>;
  /** Autore e motivo per le operazioni manuali (RF-140). */
  audit?: { user: string; reason: string };
}

export interface TimelineProps {
  events: TimelineEvent[];
  filters: FilterBuilderProps;
  onLoadMore?: () => void;
}
