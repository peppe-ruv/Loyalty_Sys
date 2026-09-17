/**
 * Elementi minimi condivisi dai pattern: chip, bottoni, contatore ad anello, badge di stato.
 *
 * Non sono un pattern del catalogo: sono i mattoni che i pattern usano, tenuti qui perché una
 * chip disegnata due volte in due moduli diventa due chip diverse alla prima modifica.
 */

import type { ReactNode } from 'react';
import type { EntityRef } from '../common.js';
import { formatCount, formatDelta, formatValue, type FormatOptions } from '../format.js';

export type Tone = 'neutral' | 'accent' | 'ok' | 'warn' | 'bad';

export interface ChipProps {
  children: ReactNode;
  tone?: Tone | undefined;
  /** Se presente, la chip mostra la «×» e chiama questa funzione (LG-05). */
  onRemove?: (() => void) | undefined;
  title?: string | undefined;
}

export function Chip({ children, tone = 'neutral', onRemove, title }: ChipProps) {
  const className = tone === 'neutral' ? 'lh-chip' : `lh-chip lh-chip--${tone}`;
  return (
    <span className={className} title={title}>
      {children}
      {onRemove ? (
        <button type="button" className="lh-chip__remove" onClick={onRemove} aria-label="Rimuovi">
          ×
        </button>
      ) : null}
    </span>
  );
}

export interface ButtonProps {
  children: ReactNode;
  onClick?: (() => void) | undefined;
  variant?: 'default' | 'primary' | 'destructive' | 'quiet' | undefined;
  disabled?: boolean | undefined;
  type?: 'button' | 'submit' | undefined;
  title?: string | undefined;
}

export function Button({ children, onClick, variant = 'default', disabled, type = 'button', title }: ButtonProps) {
  const className = variant === 'default' ? 'lh-button' : `lh-button lh-button--${variant}`;
  return (
    <button type={type} className={className} onClick={onClick} disabled={disabled} title={title}>
      {children}
    </button>
  );
}

/**
 * Contatore delle liste (LG-04): «618 (83,5%) di 740». La percentuale nasce sempre da
 * filtrate/totale, mai da un conteggio a parte: è il motivo per cui la formattazione sta in
 * `format.ts` e non nel componente.
 */
export function RingCounter({ filtered, total, noun = 'elementi' }: { filtered: number; total: number; noun?: string | undefined }) {
  return (
    <p className="lh-counter">
      <strong>{formatCount(filtered, total)}</strong> {noun}
    </p>
  );
}

/** Variazione rispetto al periodo precedente, con il verso già scritto nel colore (LG-41). */
export function Delta({ current, previous }: { current: number; previous: number }) {
  const direction = current === previous ? 'flat' : current > previous ? 'up' : 'down';
  return <span className={`lh-delta--${direction}`}>{formatDelta(current, previous)}</span>;
}

/** Valore formattato secondo l'unità della colonna o del KPI. */
export function Value({ value, ...options }: { value: number } & FormatOptions) {
  return <span>{formatValue(value, options)}</span>;
}

/**
 * Riferimento a un'altra entità: si mostra la label, mai l'id (RF-139). Se il modulo passa
 * `onNavigate` diventa cliccabile, altrimenti resta testo.
 */
export function EntityLink({ entity, onNavigate }: { entity: EntityRef; onNavigate?: ((ref: EntityRef) => void) | undefined }) {
  if (!onNavigate) return <span>{entity.label}</span>;
  return (
    <button type="button" className="lh-button lh-button--quiet" onClick={() => { onNavigate(entity); }}>
      {entity.label}
    </button>
  );
}
