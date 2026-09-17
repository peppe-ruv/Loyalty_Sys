/**
 * Pattern 1 e 2 — DataTable e FilterBuilder (LG-04, LG-05) come componenti.
 *
 * Il contratto è quello di `data-table.ts`: qui c'è l'implementazione che tutti i moduli usano,
 * perché una lista ridisegnata modulo per modulo diverge al primo requisito nuovo.
 * Il componente è controllato: ordinamento, pagina e filtri li tiene il modulo, che sa da dove
 * arrivano i dati (una query, un'API, una simulazione).
 */

import type { Column, DataTableProps, TableRow } from '../data-table.js';
import type { FilterBuilderProps } from '../filters.js';
import { EmptyState } from './feedback.js';
import { Button, Chip, RingCounter } from './primitives.js';

/** Chip dei filtri attivi: ogni chip è una frase, mai un id (RF-139), e si toglie da sola. */
export function FilterChips({ chips, onChange, attributes }: FilterBuilderProps) {
  if (chips.length === 0) {
    return <p className="lh-muted">Nessun filtro attivo: la lista mostra tutto.</p>;
  }
  return (
    <div className="lh-row" role="group" aria-label="Filtri attivi">
      {chips.map((chip) => (
        <Chip
          key={`${chip.attribute}-${chip.operator}-${chip.display}`}
          tone="accent"
          onRemove={() => { onChange(chips.filter((c) => c !== chip)); }}
          title={attributes.find((a) => a.key === chip.attribute)?.label}
        >
          {chip.display}
        </Chip>
      ))}
      <Button variant="quiet" onClick={() => { onChange([]); }}>
        Azzera filtri
      </Button>
    </div>
  );
}

function cellValue<Row extends TableRow>(column: Column<Row>, row: Row) {
  if (column.render) return column.render(row);
  const value: unknown = (row as unknown as Record<string, unknown>)[column.key];
  if (value === null || value === undefined) return '—';
  if (typeof value === 'number' || typeof value === 'string') return String(value);
  if (typeof value === 'boolean') return value ? 'sì' : 'no';
  // Un oggetto senza `render` finirebbe come [object Object]: meglio dichiararlo mancante.
  return '—';
}

export function DataTable<Row extends TableRow>({
  columns,
  rows,
  total,
  filtered,
  filters,
  sort,
  pagination,
  rowActions,
  selection,
  emptyState,
  caption,
}: DataTableProps<Row> & { caption?: string | undefined }) {
  const visible = columns.filter((column) => !column.hidden);
  const pages = Math.max(1, Math.ceil(filtered / Math.max(1, pagination.pageSize)));

  return (
    <section className="lh-card">
      <header className="lh-table-head">
        <div className="lh-stack">
          {caption ? <h2 className="lh-title">{caption}</h2> : null}
          <FilterChips {...filters} />
        </div>
        <RingCounter filtered={filtered} total={total} />
      </header>

      {selection?.enabled && rows.length > 0 ? (
        <div className="lh-selection-bar">
          <span>Selezione attiva</span>
          {selection.actions.map((action) => (
            <Button key={action.label} variant="quiet" onClick={() => { action.onSelect(rows.map((row) => row.id)); }}>
              {action.label} ({rows.length})
            </Button>
          ))}
        </div>
      ) : null}

      {rows.length === 0 ? (
        <EmptyState {...emptyState} />
      ) : (
        <table className="lh-table">
          {caption ? <caption className="lh-visually-hidden">{caption}</caption> : null}
          <thead>
            <tr>
              {visible.map((column) => (
                <th key={column.key} data-align={column.align ?? 'start'} scope="col">
                  {column.sortable && sort ? (
                    <button
                      type="button"
                      className="lh-sort"
                      onClick={() => {
                        sort.onChange(column.key, sort.key === column.key && sort.direction === 'asc' ? 'desc' : 'asc');
                      }}
                      aria-sort={sort.key === column.key ? (sort.direction === 'asc' ? 'ascending' : 'descending') : 'none'}
                    >
                      {header(column)}
                      {sort.key === column.key ? (sort.direction === 'asc' ? ' ↑' : ' ↓') : ''}
                    </button>
                  ) : (
                    header(column)
                  )}
                </th>
              ))}
              {rowActions ? <th scope="col" aria-label="Azioni" /> : null}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.id} data-inactive={row.inactive === true ? 'true' : undefined}>
                {visible.map((column) => (
                  <td key={column.key} data-align={column.align ?? 'start'}>
                    {cellValue(column, row)}
                  </td>
                ))}
                {rowActions ? (
                  <td data-align="end">
                    {rowActions(row).map((action) => (
                      <Button
                        key={action.label}
                        variant={action.destructive === true ? 'destructive' : 'quiet'}
                        onClick={action.onSelect}
                      >
                        {action.label}
                      </Button>
                    ))}
                  </td>
                ) : null}
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <footer className="lh-pagination">
        <span>
          Pagina {pagination.page} di {pages}
        </span>
        <span className="lh-row">
          <Button
            onClick={() => { pagination.onChange(pagination.page - 1, pagination.pageSize); }}
            disabled={pagination.page <= 1}
          >
            Precedente
          </Button>
          <Button
            onClick={() => { pagination.onChange(pagination.page + 1, pagination.pageSize); }}
            disabled={pagination.page >= pages}
          >
            Successiva
          </Button>
        </span>
      </footer>
    </section>
  );
}

/** Intestazione con l'unità tra parentesi: «Spesa media (EUR)» (LG-04). */
function header<Row extends TableRow>(column: Column<Row>): string {
  return column.unit === undefined ? column.header : `${column.header} (${column.unit})`;
}
