/**
 * Pattern 1 — DataTable (LG-04, LG-05): la tabella standard di ogni lista del backoffice.
 */

import type { ReactNode } from 'react';
import type { Unit } from './common.js';
import type { FilterBuilderProps } from './filters.js';
import type { EmptyStateProps } from './feedback.js';

export type SortDirection = 'asc' | 'desc';

export interface Column<Row> {
  key: string;
  header: string;
  /** Unità mostrata nell'intestazione: «Spesa media (EUR)». */
  unit?: Unit;
  align?: 'start' | 'end';
  sortable?: boolean;
  /** Colonne nascoste per default; la preferenza per utente è salvata dal componente. */
  hidden?: boolean;
  render?: (row: Row) => ReactNode;
}

/** Riga minima: l'id serve alla selezione, `inactive` ingrigisce la riga (LG-04). */
export interface TableRow {
  id: string;
  inactive?: boolean;
}

export interface RowAction {
  label: string;
  onSelect: () => void;
  destructive?: boolean;
}

export interface DataTableProps<Row extends TableRow> {
  columns: Array<Column<Row>>;
  rows: Row[];
  /** Totale della lista senza filtri, per il contatore ad anello «618 (83,5%) di 740». */
  total: number;
  /** Righe che soddisfano i filtri correnti. */
  filtered: number;
  filters: FilterBuilderProps;
  search?: { columns: string[]; placeholder?: string; onChange: (query: string, column?: string) => void };
  sort?: { key: string; direction: SortDirection; onChange: (key: string, direction: SortDirection) => void };
  pagination: { page: number; pageSize: number; onChange: (page: number, pageSize: number) => void };
  /** Menu ⋮ per riga. */
  rowActions?: (row: Row) => RowAction[];
  /** Modalità selezione con barra azioni e contatore («Esporta selezionati (3)»). */
  selection?: { enabled: boolean; actions: Array<{ label: string; onSelect: (ids: string[]) => void }> };
  /** Chiave con cui persistere colonne visibili e ordine per utente. */
  preferencesKey: string;
  emptyState: EmptyStateProps;
}
