/**
 * Pattern 15 — EmptyState (LG-45, LG-27) e riscontri delle operazioni (LG-09).
 */

import type { ReactNode } from 'react';

export interface EmptyStateProps {
  icon?: ReactNode;
  /** Titolo d'azione: «Aggiungi la prima condizione». */
  title: string;
  /** Una frase su cosa succede dopo. */
  description: string;
  primaryAction?: { label: string; onClick: () => void };
  guideHref?: string;
}

export interface Toast {
  tone: 'success' | 'error' | 'info';
  message: string;
  href?: string;
}

/** Operazione lunga (import, ricalcolo tier, export): modale con il link per seguirne l'esito. */
export interface LongOperationModal {
  title: string;
  message: string;
  followHref: string;
}
