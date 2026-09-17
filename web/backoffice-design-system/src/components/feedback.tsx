/**
 * Pattern 15 — EmptyState e riscontri delle operazioni (LG-09, LG-45).
 *
 * Uno stato vuoto non dice «nessun risultato»: dice qual è il primo passo. È la differenza fra una
 * schermata che sembra rotta e una che insegna a usarla.
 */

import type { EmptyStateProps, LongOperationModal, Toast } from '../feedback.js';
import { Button } from './primitives.js';

export function EmptyState({ icon, title, description, primaryAction, guideHref }: EmptyStateProps) {
  return (
    <div className="lh-empty">
      {icon ? <div aria-hidden="true">{icon}</div> : null}
      <h3 className="lh-empty__title">{title}</h3>
      <p>{description}</p>
      <div className="lh-row" style={{ justifyContent: 'center' }}>
        {primaryAction ? (
          <Button variant="primary" onClick={primaryAction.onClick}>
            {primaryAction.label}
          </Button>
        ) : null}
        {guideHref !== undefined ? <a href={guideHref}>Come funziona</a> : null}
      </div>
    </div>
  );
}

export function ToastView({ tone, message, href }: Toast) {
  return (
    <div className={`lh-toast lh-toast--${tone}`} role="status">
      <span>{message}</span>
      {href !== undefined ? <a href={href}>Vedi</a> : null}
    </div>
  );
}

/**
 * Operazione lunga (import, ricalcolo dei livelli, export): non si blocca l'operatore con uno
 * spinner, gli si dà dove seguirne l'esito (LG-45).
 */
export function LongOperationNotice({ title, message, followHref }: LongOperationModal) {
  return (
    <div className="lh-card" role="status">
      <h3 className="lh-title">{title}</h3>
      <p className="lh-muted">{message}</p>
      <a href={followHref}>Segui l&apos;avanzamento</a>
    </div>
  );
}
