"use client";

import { cn } from "@/lib/cn";

// Tabella dati semplice (docs/08 §3.1). Ordinamento/paginazione lato server li aggiungono le viste che li usano.
export interface Column<T> {
  key: string;
  header: string;
  render: (row: T) => React.ReactNode;
  className?: string;
}

export function DataTable<T>({
  columns,
  rows,
  rowKey,
  onRowClick,
}: {
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  onRowClick?: (row: T) => void;
}) {
  return (
    <div className="overflow-x-auto rounded-md border border-[var(--color-bo-border)]">
      <table className="w-full border-collapse text-sm">
        <thead>
          <tr className="border-b border-[var(--color-bo-border)] bg-slate-50 text-left text-xs uppercase tracking-wide text-[var(--color-bo-ink-2)]">
            {columns.map((c) => (
              <th key={c.key} className={cn("px-3 py-2 font-medium", c.className)}>
                {c.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr
              key={rowKey(row)}
              onClick={onRowClick ? () => onRowClick(row) : undefined}
              className={cn(
                "border-b border-[var(--color-bo-border)] last:border-0",
                onRowClick && "cursor-pointer hover:bg-slate-50",
              )}
            >
              {columns.map((c) => (
                <td key={c.key} className={cn("px-3 py-2 align-middle", c.className)}>
                  {c.render(row)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
